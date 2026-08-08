package com.example.voiceinteractionappsample.session

import android.Manifest
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.example.voiceinteractionappsample.audio.WebRtcAudioEngine
import com.example.voiceinteractionappsample.realtime.MockRealtimeCredentialProvider
import com.example.voiceinteractionappsample.realtime.RealtimeEventCodec
import com.example.voiceinteractionappsample.realtime.RealtimeWebRtcClient
import com.example.voiceinteractionappsample.realtime.WebRtcFactoryProvider
import com.example.voiceinteractionappsample.realtime.getStatsSuspend
import java.time.Instant
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver

/**
 * Full-duplex ground truth (4-1, 7節 full duplex 成立条件): does audio actually flow BOTH
 * directions at once against the real OpenAI Realtime API, not just "does negotiation succeed".
 *
 * Needs an ephemeral client secret minted OUTSIDE the device, same as
 * RealtimeWebRtcClientLiveTest. Skips (does not fail) when not supplied:
 *
 * ```
 * adb shell am instrument -w \
 *   -e openaiEphemeralSecret <secret> \
 *   com.example.voiceinteractionappsample.session.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 */
@RunWith(AndroidJUnit4::class)
class FullDuplexAudioLiveTest {

    @get:Rule
    val permissionRule: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)

    @Test
    fun micAndAssistantAudioBothFlowConcurrently() = runBlocking {
        val secret = InstrumentationRegistry.getArguments().getString("openaiEphemeralSecret")
        assumeNotNull(secret)

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val audioDeviceModule = WebRtcAudioEngine.create(context)
        val factory = WebRtcFactoryProvider.create(context, audioDeviceModule)
        val audioSource = factory.createAudioSource(MediaConstraints())
        val localTrack = factory.createAudioTrack("mic0", audioSource)

        val credentialProvider = MockRealtimeCredentialProvider(
            clientSecret = secret!!,
            expiresAt = Instant.now().plusSeconds(60),
        )
        val client = RealtimeWebRtcClient(factory, credentialProvider)

        val connection = client.connect(NoOpObserverWithTrackLogging(), localTrack)
        val peerConnection = connection.peerConnection
        try {
            withTimeout(10_000) { connection.events.awaitOpen() }

            // Default session may already output audio, but be explicit — this is the whole
            // point of the test (7-1節: assistant audio playback + mic capture concurrently).
            connection.events.send(
                RealtimeEventCodec.encodeSessionUpdate(
                    JSONObject()
                        .put("type", "realtime")
                        .put("output_modalities", JSONArray(listOf("audio")))
                )
            )
            // No real user speech to trigger server VAD reliably in this environment —
            // force the assistant to speak so we have something to measure inbound audio on.
            connection.events.send(JSONObject().put("type", "response.create").toString())

            // Give WebRTC a few seconds to actually exchange audio RTP packets.
            delay(6_000)

            val stats = peerConnection.getStatsSuspend()
            var audioBytesSent = 0.0
            var audioBytesReceived = 0.0
            for (stat in stats.statsMap.values) {
                val kind = stat.members["kind"] as? String
                if (kind != "audio") continue
                when (stat.type) {
                    "outbound-rtp" -> audioBytesSent += (stat.members["bytesSent"] as? Number)?.toDouble() ?: 0.0
                    "inbound-rtp" -> audioBytesReceived += (stat.members["bytesReceived"] as? Number)?.toDouble() ?: 0.0
                }
            }

            assertTrue("expected mic audio bytesSent > 0, stats: ${stats.statsMap}", audioBytesSent > 0)
            assertTrue("expected assistant audio bytesReceived > 0, stats: ${stats.statsMap}", audioBytesReceived > 0)
        } finally {
            connection.events.close()
            peerConnection.close()
            audioSource.dispose()
        }
    }
}

/** Logs nothing by default; only exists so addTrack's automatic remote-stream callbacks don't NPE. */
private open class NoOpObserverWithTrackLogging : PeerConnection.Observer {
    override fun onSignalingChange(newState: PeerConnection.SignalingState?) = Unit
    override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) = Unit
    override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
    override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState?) = Unit
    override fun onIceCandidate(candidate: IceCandidate?) = Unit
    override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) = Unit
    override fun onAddStream(stream: MediaStream?) = Unit
    override fun onRemoveStream(stream: MediaStream?) = Unit
    override fun onDataChannel(dataChannel: DataChannel?) = Unit
    override fun onRenegotiationNeeded() = Unit
    override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out MediaStream>?) = Unit
    override fun onTrack(transceiver: RtpTransceiver?) = Unit
}
