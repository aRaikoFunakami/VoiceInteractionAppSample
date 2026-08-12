package com.example.voiceinteractionappsample.realtime

import android.os.SystemClock
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.MediaConstraints
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RTCStatsReport
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription

class RealtimeConnectionException(message: String) : Exception(message)

/** [peerConnection] + the "oai-events" DataChannel (4節, 3-4節), returned together from [RealtimeWebRtcClient.connect]. */
data class RealtimeConnection(
    val peerConnection: PeerConnection,
    val events: RealtimeEventChannel,
)

/**
 * PeerConnection管理 + SDP offer/answer (4節, 1節).
 *
 * DataChannel / event decode-encode / session.update / function call handling are separate
 * (3-4) — this class only gets the PeerConnection through SDP exchange with OpenAI Realtime.
 *
 * Verified live 2026-08 against https://api.openai.com/v1/realtime/calls
 * (docs/dev-plan.md, ticket 3-3): the answer SDP is the raw response body
 * (Content-Type: application/sdp), not JSON — matches the plan, not the older
 * `/v1/realtime/sessions` flow some blog posts still describe.
 */
class RealtimeWebRtcClient(
    private val peerConnectionFactory: PeerConnectionFactory,
    private val credentialProvider: RealtimeCredentialProvider,
    // issue #43: configurable so callers can point at a local Realtime-compatible server
    // instead of OpenAI's. Defaults keep every existing call site unchanged.
    private val realtimeCallsUrl: String = DEFAULT_REALTIME_CALLS_URL,
) {
    /**
     * @param localAudioTrack Captured via WebRtcAudioEngine's AudioDeviceModule — this class
     *   does not capture audio itself, only wires the track :session hands it (4-1). When
     *   null (e.g. [RealtimeWebRtcClientLiveTest]), an empty sendrecv audio transceiver is
     *   still added: OpenAI Realtime rejects an offer with no audio media section at all
     *   (verified live 2026-08: HTTP 400 invalid_offer "Offer did not have an audio media
     *   section").
     */
    suspend fun connect(
        observer: PeerConnection.Observer,
        localAudioTrack: AudioTrack? = null,
    ): RealtimeConnection {
        // コードレビューで発見: 一時期credential取得をローカルのWebRTCセットアップと並列化
        // していたが (a) 実測でinitPlayout/ICEの遅延はaddTrack()ではなくsetRemoteDescription()
        // に律速されており並列化の効果が測定上ゼロだったこと (b) credential取得がローカル
        // セットアップ完了後に失敗すると、生成済みのPeerConnection/DataChannelを閉じる手段が
        // どこにも無く（connect()にtry/catch/finallyが無く、失敗時はConversationController側
        // も`connection`フィールドを一切受け取れないためcancel()の掃除対象にできない）
        // ネイティブリソースがリークすること、の2点から元の逐次順序に戻す。credential取得を
        // 最初に行うことで、失敗時にWebRTCオブジェクトが一切生成されていない状態を保証する
        // （このリスクを完全に無くすのが目的で、"順序を入れ替えるだけ"の中途半端な直しだと
        // このリークは残ったままになる）。各段階の所要時間ログは調査に有用なので維持する。
        val t0 = SystemClock.elapsedRealtime()
        val credential = credentialProvider.fetchCredential()
        Log.i(TAG, "credential fetch took ${SystemClock.elapsedRealtime() - t0}ms")

        val rtcConfig = PeerConnection.RTCConfiguration(emptyList())
        val peerConnection = peerConnectionFactory.createPeerConnection(rtcConfig, observer)
            ?: throw RealtimeConnectionException("createPeerConnection returned null")

        if (localAudioTrack != null) {
            peerConnection.addTrack(localAudioTrack)
        } else {
            peerConnection.addTransceiver(
                MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO,
                RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_RECV),
            )
        }

        // The DataChannel must exist before createOffer() for it to end up in the SDP (4節:
        // session.update / conversation / response / function call events all go over this).
        val dataChannel = peerConnection.createDataChannel(DATA_CHANNEL_LABEL, DataChannel.Init())
            ?: throw RealtimeConnectionException("createDataChannel returned null")

        val offer = peerConnection.createOfferSuspend()
        peerConnection.setLocalDescriptionSuspend(offer)
        Log.i(TAG, "local offer ready at ${SystemClock.elapsedRealtime() - t0}ms")

        val answerSdp = withContext(Dispatchers.IO) {
            postOffer(credential.clientSecret, offer.description)
        }
        Log.i(TAG, "postOffer() returned answer at ${SystemClock.elapsedRealtime() - t0}ms")
        peerConnection.setRemoteDescriptionSuspend(
            SessionDescription(SessionDescription.Type.ANSWER, answerSdp)
        )
        Log.i(TAG, "setRemoteDescription done at ${SystemClock.elapsedRealtime() - t0}ms")

        return RealtimeConnection(peerConnection, RealtimeEventChannel(dataChannel))
    }

    private fun postOffer(clientSecret: String, offerSdp: String): String {
        val connection = (URL(realtimeCallsUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15_000
            readTimeout = 15_000
            setRequestProperty("Authorization", "Bearer $clientSecret")
            setRequestProperty("Content-Type", "application/sdp")
        }
        try {
            connection.outputStream.use { it.write(offerSdp.toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            if (status !in 200..299) {
                val error = connection.errorStream?.bufferedReader()?.readText().orEmpty()
                throw RealtimeConnectionException("SDP exchange failed: HTTP $status $error")
            }
            return connection.inputStream.bufferedReader().readText()
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        const val DEFAULT_REALTIME_CALLS_URL = "https://api.openai.com/v1/realtime/calls"
        private const val TAG = "RealtimeWebRtcClient"
        private const val DATA_CHANNEL_LABEL = "oai-events"
    }
}

/**
 * WebRTC-level ground truth for "is audio actually flowing" (diagnostics 20節, ticket 4-1):
 * inbound-rtp audio bytesReceived / outbound-rtp audio bytesSent growing over time is how we
 * confirm mic capture and assistant playback without a human listening.
 */
suspend fun PeerConnection.getStatsSuspend(): RTCStatsReport =
    suspendCancellableCoroutine { cont ->
        getStats { report -> cont.resume(report) }
    }

private suspend fun PeerConnection.createOfferSuspend(): SessionDescription =
    suspendCancellableCoroutine { cont ->
        createOffer(
            object : SdpObserver {
                override fun onCreateSuccess(description: SessionDescription?) {
                    if (description != null) {
                        cont.resume(description)
                    } else {
                        cont.resumeWithException(
                            RealtimeConnectionException("createOffer succeeded with null description")
                        )
                    }
                }

                override fun onCreateFailure(error: String?) {
                    cont.resumeWithException(RealtimeConnectionException("createOffer failed: $error"))
                }

                override fun onSetSuccess() = Unit
                override fun onSetFailure(error: String?) = Unit
            },
            MediaConstraints(),
        )
    }

private suspend fun PeerConnection.setLocalDescriptionSuspend(description: SessionDescription): Unit =
    suspendCancellableCoroutine { cont ->
        setLocalDescription(
            object : SdpObserver {
                override fun onSetSuccess() {
                    cont.resume(Unit)
                }

                override fun onSetFailure(error: String?) {
                    cont.resumeWithException(RealtimeConnectionException("setLocalDescription failed: $error"))
                }

                override fun onCreateSuccess(description: SessionDescription?) = Unit
                override fun onCreateFailure(error: String?) = Unit
            },
            description,
        )
    }

private suspend fun PeerConnection.setRemoteDescriptionSuspend(description: SessionDescription): Unit =
    suspendCancellableCoroutine { cont ->
        setRemoteDescription(
            object : SdpObserver {
                override fun onSetSuccess() {
                    cont.resume(Unit)
                }

                override fun onSetFailure(error: String?) {
                    cont.resumeWithException(RealtimeConnectionException("setRemoteDescription failed: $error"))
                }

                override fun onCreateSuccess(description: SessionDescription?) = Unit
                override fun onCreateFailure(error: String?) = Unit
            },
            description,
        )
    }
