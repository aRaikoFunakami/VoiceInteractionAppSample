package com.example.voiceinteractionappsample.localagent

import com.example.localvoiceagent.LlmEngine
import com.example.localvoiceagent.LocalAudioEngine
import com.example.localvoiceagent.stt.SenseVoiceRecognizer
import com.example.localvoiceagent.tts.SupertonicTts
import com.example.localvoiceagent.tts.TtsPlayer
import com.example.voiceinteractionappsample.session.LoadingEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll

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
     * LLM ロード + STT/TTS の遅延初期化先出し。3 エンジンとも互いに依存しないため並列実行
     * (直列だと合計時間がかかっていた)。初回のみ数秒〜10 秒ブロック(呼び出し元は Main 以外で
     * あること)。2 回目以降は即 return — [onEngineReady] はキャッシュヒット時は呼ばれない
     * (先出しロードは 1 回しか走らないため)。
     */
    suspend fun ensureInitialized(onEngineReady: (LoadingEngine) -> Unit = {}) {
        val d = initDeferred ?: synchronized(this) {
            initDeferred ?: runtimeScope.async {
                listOf(
                    async { llm.initialize(); onEngineReady(LoadingEngine.LLM) },
                    async { stt.warmUp(); onEngineReady(LoadingEngine.STT) },
                    async { ttsEngine.warmUp(); onEngineReady(LoadingEngine.TTS) },
                ).awaitAll()
                Unit
            }.also { initDeferred = it }
        }
        d.await()
    }

    /** 実行中の LLM 推論を打ち切る(cancel 経路用。未初期化なら no-op)。 */
    fun cancelInference() {
        if (initDeferred?.isCompleted == true) llm.cancelActive()
    }

    /** セッションがアクティブか(LocalAgentController が start/cancel で更新)。 */
    @Volatile var sessionActive: Boolean = false
        internal set

    /**
     * メモリ逼迫時の解放パス(issue #49, 計画書 §3.4)。セッション非アクティブ時のみ
     * LLM(常駐 ~数 GB)を解放し、次回 ensureInitialized() でクリーンに再ロードさせる。
     * LMK に強制終了されるより能動的に手放す方が安全。STT/TTS(~360MB)は再ロードが
     * 速いので保持したままにする。
     */
    fun onTrimMemory(level: Int) {
        if (sessionActive) return
        // 実機で確認: VoiceInteractionService にバインドされたプロセスは常時 foreground 扱いで、
        // BACKGROUND/COMPLETE 系のレベルは配信されない(send-trim-memory も
        // "Unable to set a background trim level on a foreground process" で拒否される)。
        // foreground プロセス向けの RUNNING_CRITICAL(15) 以上で解放しないと、この解放パスは
        // 実質永久に発火しない。
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) {
            val d = initDeferred ?: return
            if (!d.isCompleted) return // ロード中に close すると native 競合(初回 PTT 中は触らない)
            android.util.Log.i("LocalAgentRuntime", "onTrimMemory($level): releasing LLM")
            initDeferred = null
            llm.close()
        }
    }
}
