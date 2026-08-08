package com.example.voiceinteractionappsample.realtime

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.webrtc.PeerConnection

/**
 * Live test against https://api.openai.com/v1/realtime/calls (real network, real OpenAI
 * account usage). Needs an ephemeral client secret minted OUTSIDE the device — the standard
 * OpenAI API key never touches Android (5節). Pass it via instrumentation args:
 *
 * ```
 * adb shell am instrument -w \
 *   -e openaiEphemeralSecret <secret> \
 *   com.example.voiceinteractionappsample.realtime.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 *
 * Skips (does not fail) when the arg isn't supplied, so no generic run needs credentials.
 */
@RunWith(AndroidJUnit4::class)
class RealtimeWebRtcClientLiveTest {

    @Test
    fun negotiatesRealAnswerFromOpenAi() = runBlocking {
        val secret = InstrumentationRegistry.getArguments().getString("openaiEphemeralSecret")
        assumeNotNull(secret)

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val factory = WebRtcFactoryProvider.create(context)
        val credentialProvider = MockRealtimeCredentialProvider(
            clientSecret = secret!!,
            expiresAt = Instant.now().plusSeconds(60),
        )
        val client = RealtimeWebRtcClient(factory, credentialProvider)

        val peerConnection = client.connect(NoOpPeerConnectionObserver())
        try {
            assertTrue(
                "expected a stable signaling state after offer/answer, was ${peerConnection.signalingState()}",
                peerConnection.signalingState() == PeerConnection.SignalingState.STABLE,
            )
            val remoteSdp = peerConnection.remoteDescription?.description.orEmpty()
            assertTrue("remote SDP answer should look like an SDP body, was: $remoteSdp", remoteSdp.contains("v=0"))
        } finally {
            peerConnection.close()
        }
    }
}
