package com.example.voiceinteractionappsample.session

import android.Manifest
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * AEC Test A (19節): assistant audio再生中、ユーザー無発話の状態で、OpenAI側が新しい
 * user speechを検出しないこと（自己音声によるエコーがserver VADを誤トリガーしない）。
 *
 * このテストは「無発話」を制御できない — ホストの実マイクが実スピーカー出力を拾うかどうかは
 * 物理的なエコー経路（Macのスピーカー/マイクの物理配置とホスト音量）に依存する。したがって
 * ここでの合否は「WebRTC/Realtime配線が正しく動くか」の確認であり、AECの合格判定そのものは
 * 19節の通り実機評価前に固定しない — 結果はdocs/aec-device-profiles.mdへ人間が判断して記録する。
 *
 * adb shell am instrument -w -e openaiEphemeralSecret <secret> \
 *   -e class com.example.voiceinteractionappsample.session.AecTestALiveTest \
 *   com.example.voiceinteractionappsample.session.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class AecTestALiveTest {

    @get:Rule
    val permissionRule: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)

    @Test
    fun noFalseUserSpeechDetectedWhileAssistantSpeaksAndNoOneTalks() = runBlocking {
        val secret = InstrumentationRegistry.getArguments().getString("openaiEphemeralSecret")
        assumeNotNull(secret)

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val connection = RealtimeLiveTestHarness.connectWithMic(context, secret!!)
        try {
            RealtimeLiveTestHarness.requestAssistantSpeech(
                connection,
                instructions = "Count slowly from one to twenty, one number per breath, so this takes a while to say.",
            )

            val events = RealtimeLiveTestHarness.collectEventsFor(connection, durationMs = 15_000)

            val falseTriggers = events.filter {
                it.type == "input_audio_buffer.speech_started" ||
                    (it.type == "conversation.item.created" && it.raw.optJSONObject("item")?.optString("role") == "user")
            }

            // Not an assertion failure — see class kdoc. Logged as instrumentation status so it
            // shows up in `am instrument` output for a human to read and record in the device
            // profile; this test's job is to surface the evidence, not rule on AEC quality.
            InstrumentationRegistry.getInstrumentation().sendStatus(
                0,
                android.os.Bundle().apply {
                    putInt("falseTriggerCount", falseTriggers.size)
                    putInt("totalEventCount", events.size)
                    putString("eventTypes", events.map { it.type }.toString())
                },
            )
        } finally {
            connection.events.close()
            connection.peerConnection.close()
        }
    }
}
