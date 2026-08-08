package com.example.voiceinteractionappsample.session

import android.Manifest
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * AEC Test B + C combined (19節): needs a real human speaking into the host mic during
 * assistant playback, so both are gathered in one run instead of asking for two.
 *
 * Test B: assistant再生中、通常音量の発話が認識されること — look for a user conversation
 * item / transcript appearing during the window.
 * Test C: 短い割り込みでresponseが中断されること — look for `input_audio_buffer.speech_started`
 * arriving BEFORE `response.done`, and whether response.done arrives earlier than a full
 * uninterrupted ~20s utterance would (Test A's baseline: response.done around 15-16s in).
 *
 * This is evidence-gathering, not pass/fail (19節: AECの合格値は実機評価前に固定しない) — logs
 * the full timed event list via instrumentation status for a human to read.
 *
 * adb shell am instrument -w -e openaiEphemeralSecret <secret> \
 *   -e class com.example.voiceinteractionappsample.session.AecTestBCLiveTest \
 *   com.example.voiceinteractionappsample.session.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class AecTestBCLiveTest {

    @get:Rule
    val permissionRule: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)

    @Test
    fun speechDuringPlaybackIsRecognizedAndCanInterrupt() = runBlocking {
        val secret = InstrumentationRegistry.getArguments().getString("openaiEphemeralSecret")
        assumeNotNull(secret)

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val connection = RealtimeLiveTestHarness.connectWithMic(context, secret!!)
        try {
            RealtimeLiveTestHarness.requestAssistantSpeech(
                connection,
                instructions = "Count slowly from one to thirty, one number per breath, so this takes a while to say.",
            )

            // Generous window: whoever is speaking doesn't get a precise "now" signal.
            val timedEvents = RealtimeLiveTestHarness.collectTimedEventsFor(connection, durationMs = 25_000)

            val speechStartedAt = timedEvents.firstOrNull { it.second.type == "input_audio_buffer.speech_started" }?.first
            val responseDoneAt = timedEvents.firstOrNull { it.second.type == "response.done" }?.first
            val userItemAt = timedEvents.firstOrNull {
                it.second.type == "conversation.item.added" && it.second.raw.optJSONObject("item")?.optString("role") == "user"
            }?.first
            val transcriptEvent = timedEvents.firstOrNull {
                it.second.type.contains("input_audio_transcription", ignoreCase = true)
            }

            InstrumentationRegistry.getInstrumentation().sendStatus(
                0,
                Bundle().apply {
                    putString("speechStartedAtMs", speechStartedAt?.toString() ?: "none")
                    putString("userConversationItemAtMs", userItemAt?.toString() ?: "none")
                    putString("responseDoneAtMs", responseDoneAt?.toString() ?: "none")
                    putString("transcriptEventType", transcriptEvent?.second?.type ?: "none")
                    putString("transcriptRaw", transcriptEvent?.second?.raw?.toString() ?: "none")
                    putString("fullTimeline", timedEvents.joinToString("\n") { "${it.first}ms ${it.second.type}" })
                },
            )
        } finally {
            connection.events.close()
            connection.peerConnection.close()
        }
    }
}
