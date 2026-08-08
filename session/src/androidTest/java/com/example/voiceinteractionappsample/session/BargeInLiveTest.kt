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
                instructions = "Describe, in great detail and very slowly, everything about how weather " +
                    "forecasting works. Keep talking without stopping for at least three minutes.",
            )

            val timedEvents = RealtimeLiveTestHarness.collectTimedEventsFor(connection, durationMs = 45_000)

            val speechStartedAt = timedEvents.firstOrNull { it.second.type == "input_audio_buffer.speech_started" }?.first
            // The response.done that matters is the one AFTER speech was detected — not just
            // the first response.done anywhere in the window (a run where the human reacts
            // slowly enough that the *original* response already finished on its own, then a
            // second response cycle starts, isn't "interrupted", it's just two turns in a row;
            // comparing against the wrong response.done makes that misread as a failure).
            val responseDoneAfterSpeechAt = speechStartedAt?.let { started ->
                timedEvents.filter { it.second.type == "response.done" && it.first >= started }
                    .minByOrNull { it.first }?.first
            }

            assertNotNull(
                "expected input_audio_buffer.speech_started — no speech was detected at all " +
                    "(did you actually speak during the ~45s window?)",
                speechStartedAt,
            )
            assertNotNull(
                "expected a response.done AFTER speech_started ($speechStartedAt ms) — either the " +
                    "original response already finished before you spoke (talk sooner / ask for a " +
                    "longer response), or interruption isn't happening",
                responseDoneAfterSpeechAt,
            )
        } finally {
            connection.events.close()
            connection.peerConnection.close()
        }
    }
}
