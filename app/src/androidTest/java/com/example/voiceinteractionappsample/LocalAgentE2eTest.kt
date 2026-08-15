package com.example.voiceinteractionappsample

import android.Manifest
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.localvoiceagent.stt.SpeechRecognizer
import com.example.voiceinteractionappsample.localagent.LocalAgentController
import com.example.voiceinteractionappsample.localagent.LocalAgentRuntime
import com.example.voiceinteractionappsample.session.AudioOutputState
import com.example.voiceinteractionappsample.session.ConnectionState
import com.example.voiceinteractionappsample.session.DisconnectReason
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/**
 * LOCAL_AGENT の実モデル E2E(issue #48)。モデル 3 種が /data/local/tmp に push 済みの
 * AAOS AVD で実行する(未配置なら assume でスキップ)。
 *
 * 音響経路(実マイク発話)はエミュレータでは検証できないため、STT のみ fake を注入して
 * 「確定発話イベント以降」のフルパス(実 Gemma 推論 → 実 supertonic 合成 → 実 AudioTrack 再生)
 * を検証する。実音声での会話・バージインは人間による実機確認(計画 §8.2)。
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class LocalAgentE2eTest {

    @get:Rule
    val micPermission: androidx.test.rule.GrantPermissionRule =
        androidx.test.rule.GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    // Android 15 の audio focus hardening: TOP でないプロセスの focus 要求は
    // AS.HardeningEnforcer が DENY する(実機ログで確認)。本番は VoiceInteractionSession の
    // 表示で TOP になるが、テストプロセスは可視 Activity を持たないため、
    // 自アプリの Activity を起動して TOP 状態を作る(古い androidx.test では
    // ActivityScenario が版ずれで使えないため素の Intent で起動する)。
    @org.junit.Before
    fun bringProcessToTop() {
        context.startActivity(
            android.content.Intent(context, ServerSettingsActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        Thread.sleep(1500) // resume 待ち(procState が TOP になるまで)
    }

    private class InjectableStt : SpeechRecognizer {
        @Volatile var speechActive = false
        override var onFinalResult: ((String) -> Unit)? = null
        override fun acceptAudio(samples: ShortArray, sampleRate: Int) = Unit
        override fun isSpeechActive(): Boolean = speechActive
        override fun reset() = Unit
        override fun close() = Unit
    }

    private fun awaitState(
        controller: LocalAgentController,
        timeoutMs: Long,
        what: String,
        predicate: () -> Boolean,
    ) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (!predicate()) {
            assertTrue(
                "timeout waiting for $what (state=${controller.state.value})",
                SystemClock.elapsedRealtime() < deadline,
            )
            Thread.sleep(100)
        }
    }

    @Test
    fun test1_startOnMainScope_doesNotBlockMainThread_andConnects() {
        assumeTrue("models not pushed", LocalAgentRuntime.modelsAvailable())
        val stt = InjectableStt()
        val controller = LocalAgentController(context = context, stt = stt)

        // 本番同様 Main スコープから start() を launch(CarVoiceInteractionSession と同じ形)
        val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        // Main スレッドの応答性計測: 100ms 間隔で自分を再ポストし、最大ギャップを記録する
        val handler = Handler(Looper.getMainLooper())
        var lastBeat = SystemClock.elapsedRealtime()
        var maxGapMs = 0L
        var beating = true
        val beat = object : Runnable {
            override fun run() {
                val now = SystemClock.elapsedRealtime()
                maxGapMs = maxOf(maxGapMs, now - lastBeat)
                lastBeat = now
                if (beating) handler.postDelayed(this, 100)
            }
        }
        handler.post(beat)

        mainScope.launch { controller.start() }
        // 初回はモデルロード込み(スパイク実測 init 5.5s + warmup)。60s 上限で待つ。
        awaitState(controller, 60_000, "CONNECTED") {
            controller.state.value.connection == ConnectionState.CONNECTED
        }
        beating = false

        val s = controller.state.value
        assertEquals(LocalAgentController.GREETING_TEXT, s.assistantTranscript)
        Log.i(TAG, "connected. main-thread max gap = ${maxGapMs}ms")
        // Main が数秒塞がれていたら ANR 一直線。多少の GC/emulator ジッタは許して 1s を上限に。
        assertTrue("main thread blocked: max gap ${maxGapMs}ms", maxGapMs < 1_000)

        runBlocking { controller.cancel(DisconnectReason.USER_CANCEL) }
        assertEquals(ConnectionState.DISCONNECTED, controller.state.value.connection)
    }

    @Test
    fun test2_injectedUtterance_runsRealLlmAndTts_fullTurn() {
        assumeTrue("models not pushed", LocalAgentRuntime.modelsAvailable())
        val stt = InjectableStt()
        val controller = LocalAgentController(context = context, stt = stt)
        runBlocking { controller.start() }
        assertEquals(ConnectionState.CONNECTED, controller.state.value.connection)

        val t0 = SystemClock.elapsedRealtime()
        stt.onFinalResult?.invoke("こんにちは、自己紹介してください")

        // 実 Gemma 推論 → assistantTranscript 更新 + TTS 再生開始
        awaitState(controller, 60_000, "assistant reply") {
            controller.state.value.assistantTranscript.isNotBlank() &&
                controller.state.value.assistantTranscript != LocalAgentController.GREETING_TEXT
        }
        val s = controller.state.value
        Log.i(TAG, "turn: ${SystemClock.elapsedRealtime() - t0}ms user=\"${s.userTranscript}\" ai=\"${s.assistantTranscript}\"")
        assertEquals("こんにちは、自己紹介してください", s.userTranscript)
        assertEquals(AudioOutputState.PLAYING, s.audioOutput)

        // 実 AudioTrack で再生し切って LISTENING に戻る(watchdog が検出)
        awaitState(controller, 60_000, "playback finished") {
            controller.state.value.audioOutput == AudioOutputState.IDLE
        }

        runBlocking { controller.cancel(DisconnectReason.USER_CANCEL) }
        assertEquals(ConnectionState.DISCONNECTED, controller.state.value.connection)
    }

    @Test
    fun test3_videoRequest_firesToolAndOpensYouTube() {
        assumeTrue("models not pushed", com.example.voiceinteractionappsample.localagent.LocalAgentRuntime.modelsAvailable())
        val stt = InjectableStt()
        val controller = LocalAgentController(
            context = context,
            stt = stt,
            toolExecutor = com.example.voiceinteractionappsample.tools.DeviceToolExecutor(
                listOf(com.example.voiceinteractionappsample.tools.OpenYouTubeSearchTool(context)),
            ),
        )
        runBlocking { controller.start() }
        assertEquals(ConnectionState.CONNECTED, controller.state.value.connection)

        stt.onFinalResult?.invoke("猫の動画を見せて")

        awaitState(controller, 60_000, "tool confirmation") {
            controller.state.value.assistantTranscript.isNotBlank() &&
                controller.state.value.assistantTranscript != LocalAgentController.GREETING_TEXT
        }
        val transcript = controller.state.value.assistantTranscript
        Log.i(TAG, "tool turn transcript: \"$transcript\"")
        // Chromium 起動成功なら確認文、(起動不可環境なら)失敗文 — どちらもツール分岐を通った証跡。
        // 発火自体の精度は SpikeToolCallTest(9/10, 誤発火0)で担保済み。
        assertTrue(
            "unexpected transcript: $transcript",
            transcript.startsWith("YouTubeで「") || transcript == "すみません、うまく開けませんでした。",
        )

        runBlocking { controller.cancel(DisconnectReason.USER_CANCEL) }
    }

    private companion object {
        const val TAG = "LocalAgentE2eTest"
    }
}
