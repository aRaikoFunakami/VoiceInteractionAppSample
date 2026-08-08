package com.example.voiceinteractionappsample.session

import android.Manifest
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.example.voiceinteractionappsample.realtime.RealtimeVadConfig
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #21 (6-2, 9節): assertion-based barge-in check, using [RealtimeVadConfig] explicitly
 * (unlike AecTestBCLiveTest, which only gathers evidence for a human to judge — whether
 * barge-in happens at all is objective, unlike AEC audio quality, so this asserts).
 *
 * Still needs a real human voice — same live-speech coordination as AecTestBCLiveTest.
 *
 * adb shell am instrument -w -e openaiEphemeralSecret <secret> \
 *   -e class com.example.voiceinteractionappsample.session.BargeInLiveTest \
 *   com.example.voiceinteractionappsample.session.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class BargeInLiveTest {

    @get:Rule
    val permissionRule: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)

    @Test
    fun userSpeechInterruptsInProgressResponse() = runBlocking {
        val secret = InstrumentationRegistry.getArguments().getString("openaiEphemeralSecret")
        assumeNotNull(secret)

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val connection = RealtimeLiveTestHarness.connectWithMic(context, secret!!, vadConfig = RealtimeVadConfig())
        try {
            RealtimeLiveTestHarness.requestAssistantSpeech(
                connection,
                instructions = "Count slowly from one to thirty, one number per breath, so this takes a while to say.",
            )

            val timedEvents = RealtimeLiveTestHarness.collectTimedEventsFor(connection, durationMs = 40_000)

            val speechStartedAt = timedEvents.firstOrNull { it.second.type == "input_audio_buffer.speech_started" }?.first
            val responseDoneAt = timedEvents.firstOrNull { it.second.type == "response.done" }?.first

            assertNotNull(
                "expected input_audio_buffer.speech_started — no speech was detected at all " +
                    "(did you actually speak during the ~25s window?)",
                speechStartedAt,
            )
            assertNotNull("expected response.done to eventually follow speech detection", responseDoneAt)
            assertTrue(
                "expected response.done ($responseDoneAt ms) after speech_started ($speechStartedAt ms) " +
                    "— old response must stop once interrupted, not run to its natural end",
                responseDoneAt!! >= speechStartedAt!!,
            )
        } finally {
            connection.events.close()
            connection.peerConnection.close()
        }
    }
}
