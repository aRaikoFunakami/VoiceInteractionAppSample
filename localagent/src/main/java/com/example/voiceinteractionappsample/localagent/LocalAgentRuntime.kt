package com.example.voiceinteractionappsample.localagent

import com.example.localvoiceagent.LlmEngine
import com.example.localvoiceagent.LocalAudioEngine
import com.example.localvoiceagent.stt.SenseVoiceRecognizer
import com.example.localvoiceagent.tts.SupertonicTts
import com.example.localvoiceagent.tts.TtsPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async

/**
 * 推論エンジン群のプロセススコープ管理(issue #48, docs/local-voice-agent-dev-plan.md §3.4)。
 *
 * LlmEngine.initialize() は数秒かかる。VIA は設定の即時反映のため PTT ごとに controller を
 * 作り直す設計なので、エンジンを controller に持たせると PTT ごとにロード待ちが発生する。
 * エンジン群はここでプロセス生存にし、controller はセッションごとの配線だけを持つ。
 *
 * - すべて lazy: LOCAL_AGENT モードを一度も使わなければ何もロードしない(OPENAI モードへの影響ゼロ)
 * - [ensureInitialized] はキャンセル安全: async で開始したロードは await() 側がキャンセル
 *   されても続行され、二重ロードやハンドルの多重生成が起きない(PTT 連打対策)
 * - [modelsAvailable]/[engineLoaded] は companion static / File チェックのみで、
 *   エンジンインスタンスを一切構築しない(:app の設定画面から安全に呼べる)
 */
object LocalAgentRuntime {
    val llm: LlmEngine by lazy { LlmEngine() }
    val stt: SenseVoiceRecognizer by lazy { SenseVoiceRecognizer() }
    val ttsEngine: SupertonicTts by lazy { SupertonicTts() }
    val ttsPlayer: TtsPlayer by lazy { TtsPlayer(ttsEngine) }

    /** 3 エンジンのモデルが配置済みか。インスタンスを構築しない(設定画面からも呼ばれる)。 */
    fun modelsAvailable(): Boolean =
        LlmEngine.modelAvailable() &&
            SenseVoiceRecognizer.modelAvailable() &&
            SupertonicTts.modelAvailable()

    /** liblocal_audio_engine.so がロード可能か(arm64-v8a のみ。x86_64 では false)。 */
    fun engineLoaded(): Boolean = LocalAudioEngine.loaded

    private val runtimeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var initDeferred: Deferred<Unit>? = null

    /**
     * LLM ロード + STT/TTS の遅延初期化先出し。初回のみ数秒〜10 秒ブロック(呼び出し元は
     * Main 以外であること)。2 回目以降は即 return。
     */
    suspend fun ensureInitialized() {
        val d = initDeferred ?: synchronized(this) {
            initDeferred ?: runtimeScope.async {
                llm.initialize()
                stt.warmUp()
                ttsEngine.warmUp()
            }.also { initDeferred = it }
        }
        d.await()
    }

    /** 実行中の LLM 推論を打ち切る(cancel 経路用。未初期化なら no-op)。 */
    fun cancelInference() {
        if (initDeferred?.isCompleted == true) llm.cancelActive()
    }
}
