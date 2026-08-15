package com.example.voiceinteractionappsample.localagent

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.localvoiceagent.stt.SpeechRecognizer
import com.example.localvoiceagent.tts.AudioSink
import com.example.localvoiceagent.tts.SpeechSynthesizer
import com.example.localvoiceagent.tts.TtsPlayer
import com.example.voiceinteractionappsample.session.AudioInputState
import com.example.voiceinteractionappsample.session.AudioOutputState
import com.example.voiceinteractionappsample.session.ConnectionState
import com.example.voiceinteractionappsample.session.ConversationSessionState
import com.example.voiceinteractionappsample.session.ConversationState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * LocalAgentController のターンループ/watchdog を、音声・モデルなしの fake 注入で検証する
 * (issue #48)。sherpa/litertlm のクラスは一切ロードしない(:localagent は compileOnly のため
 * この androidTest の実行時クラスパスにも存在しない — 触れれば NoClassDefFoundError で落ちる
 * ので、それ自体が「fake だけで完結している」ことの検証にもなっている)。
 */
@RunWith(AndroidJUnit4::class)
class LocalAgentControllerTest {

    private class FakeStt : SpeechRecognizer {
        @Volatile var speechActive = false
        override var onFinalResult: ((String) -> Unit)? = null
        override fun acceptAudio(samples: ShortArray, sampleRate: Int) = Unit
        override fun isSpeechActive(): Boolean = speechActive
        override fun reset() = Unit
        override fun close() = Unit
    }

    /** 1 秒ぶんの無音 PCM を即返す fake 合成器。 */
    private class FakeSynth : SpeechSynthesizer {
        override fun synthesize(text: String, sink: AudioSink) {
            sink.onAudio(ShortArray(48000), 48000, 1)
            sink.onEnd()
        }
        override fun close() = Unit
    }

    private fun connectedState() = ConversationSessionState(
        connection = ConnectionState.CONNECTED,
        audioInput = AudioInputState.CAPTURING,
    )

    private fun controller(
        stt: FakeStt = FakeStt(),
        ttsPlayer: TtsPlayer = TtsPlayer(FakeSynth()),
        ask: suspend (String) -> String = { "はい、わかりました。" },
    ) = LocalAgentController(
        context = InstrumentationRegistry.getInstrumentation().targetContext,
        stt = stt,
        ttsPlayer = ttsPlayer,
        ask = ask,
    )

    @Test
    fun utterance_runsFullTurn_updatingTranscriptsAndAudioState() = runBlocking {
        val c = controller(ask = { text -> "echo:$text" })
        c.seedForTest(connectedState())

        c.onUtterance("今日の天気は？")

        val s = c.state.value
        assertEquals("今日の天気は？", s.userTranscript)
        assertEquals("echo:今日の天気は？", s.assistantTranscript)
        assertEquals(AudioOutputState.PLAYING, s.audioOutput)
        assertEquals(ConversationState.IDLE, s.conversation)
    }

    @Test
    fun utteranceDuringThinking_isIgnored() = runBlocking {
        val c = controller()
        c.seedForTest(
            connectedState().copy(
                conversation = ConversationState.MODEL_PROCESSING,
                userTranscript = "最初の質問",
            ),
        )

        c.onUtterance("割り込みの質問")

        assertEquals("最初の質問", c.state.value.userTranscript) // 現ターン優先(v1 の割り切り)
    }

    @Test
    fun llmFailure_fallsBackToApologyText() = runBlocking {
        val c = controller(ask = { error("boom") })
        c.seedForTest(connectedState())

        c.onUtterance("こんにちは元気ですか")

        assertEquals(LocalAgentController.FALLBACK_TEXT, c.state.value.assistantTranscript)
        assertEquals(AudioOutputState.PLAYING, c.state.value.audioOutput)
    }

    @Test
    fun bargeIn_fourSpeechTicksDuringPlayback_cancelsTtsAndCountsInterruption() = runBlocking {
        val stt = FakeStt()
        val tts = TtsPlayer(FakeSynth())
        val c = controller(stt = stt, ttsPlayer = tts)
        c.seedForTest(connectedState().copy(audioOutput = AudioOutputState.PLAYING))

        // TTS に再生待ち音声を積んでおく(speak は同期 worker なので完了を少し待つ)
        tts.speak("これは長い応答です")
        Thread.sleep(300)
        assertTrue(tts.isSpeaking())

        stt.speechActive = true
        val now = android.os.SystemClock.elapsedRealtime()
        repeat(4) { c.watchdogTick(now) }

        val s = c.state.value
        assertEquals(1, s.interruptionCount)
        assertEquals(AudioOutputState.IDLE, s.audioOutput)
        assertEquals(0, tts.queuedFrames()) // キューは破棄済み
    }

    @Test
    fun playbackFinished_returnsToListening() = runBlocking {
        val stt = FakeStt()
        val tts = TtsPlayer(FakeSynth()) // 何も積まれていない = isSpeaking false
        val c = controller(stt = stt, ttsPlayer = tts)
        c.seedForTest(connectedState().copy(audioOutput = AudioOutputState.PLAYING))

        c.watchdogTick(android.os.SystemClock.elapsedRealtime())

        assertEquals(AudioOutputState.IDLE, c.state.value.audioOutput)
    }

    @Test
    fun idleTimeout_triggersAutoTerminate() = runBlocking {
        val c = controller()
        val base = android.os.SystemClock.elapsedRealtime()
        c.seedForTest(connectedState(), nowMs = base)

        // idle 直前は継続
        assertTrue(c.watchdogTick(base + 9_000))
        // 既定 idleTimeoutMs=10_000 超過で終了(false = ループ終了)
        assertTrue(!c.watchdogTick(base + 10_001))
    }

    @Test
    fun rawVadActivity_doesNotResetIdleTimeout() = runBlocking {
        // 生 VAD の発火(確定発話なし)ではタイムアウトが延長されないこと —
        // 車内ノイズでセッションが max まで居座るのを防ぐ設計(計画 §3.5)
        val stt = FakeStt()
        val c = controller(stt = stt)
        val base = android.os.SystemClock.elapsedRealtime()
        c.seedForTest(connectedState(), nowMs = base) // PLAYING ではないので barge-in 経路に入らない

        stt.speechActive = true
        repeat(20) { c.watchdogTick(base + 500L * it) } // VAD 発火し続けても
        assertTrue(!c.watchdogTick(base + 10_001))      // idle timeout は延長されない
    }
}
