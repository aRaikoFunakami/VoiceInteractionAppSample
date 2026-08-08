package com.example.voiceinteractionappsample.realtime

import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.webrtc.MediaConstraints
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription

class RealtimeConnectionException(message: String) : Exception(message)

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
) {
    suspend fun connect(observer: PeerConnection.Observer): PeerConnection {
        val credential = credentialProvider.fetchCredential()

        val rtcConfig = PeerConnection.RTCConfiguration(emptyList())
        val peerConnection = peerConnectionFactory.createPeerConnection(rtcConfig, observer)
            ?: throw RealtimeConnectionException("createPeerConnection returned null")

        // OpenAI Realtime rejects an offer with no audio media section (verified live,
        // 2026-08: HTTP 400 invalid_offer "Offer did not have an audio media section").
        // This only declares the m=audio line as sendrecv; wiring a captured local track
        // through WebRtcAudioEngine is :session's job (4-1), not this client's.
        peerConnection.addTransceiver(
            MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO,
            RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_RECV),
        )

        val offer = peerConnection.createOfferSuspend()
        peerConnection.setLocalDescriptionSuspend(offer)

        val answerSdp = withContext(Dispatchers.IO) {
            postOffer(credential.clientSecret, offer.description)
        }
        peerConnection.setRemoteDescriptionSuspend(
            SessionDescription(SessionDescription.Type.ANSWER, answerSdp)
        )

        return peerConnection
    }

    private fun postOffer(clientSecret: String, offerSdp: String): String {
        val connection = (URL(REALTIME_CALLS_URL).openConnection() as HttpURLConnection).apply {
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

    private companion object {
        const val REALTIME_CALLS_URL = "https://api.openai.com/v1/realtime/calls"
    }
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
