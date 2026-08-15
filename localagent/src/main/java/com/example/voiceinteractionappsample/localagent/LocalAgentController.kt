package com.example.voiceinteractionappsample.localagent

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.SystemClock
import android.util.Log
import com.example.localvoiceagent.stt.SpeechRecognizer
import com.example.localvoiceagent.tts.TtsPlayer
import com.example.localvoiceagent.audio.CapturePipeline
import com.example.localvoiceagent.audio.RenderPipeline
import com.example.voiceinteractionappsample.session.AudioInputState
import com.example.voiceinteractionappsample.session.AudioOutputState
import com.example.voiceinteractionappsample.session.ConnectionState
import com.example.voiceinteractionappsample.session.ConversationSessionState
import com.example.voiceinteractionappsample.session.ConversationState
import com.example.voiceinteractionappsample.session.DisconnectReason
import com.example.voiceinteractionappsample.session.SessionTimeoutPolicy
import com.example.voiceinteractionappsample.session.VoiceSessionController
import com.example.voiceinteractionappsample.tools.DeviceToolExecutor
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * オンデバイス local voice agent の会話コントローラ(issue #48,
 * docs/local-voice-agent-dev-plan.md §3.5)。[VoiceSessionController] の 2 つ目の実装。
 *
 *   AudioRecord → APM(AEC3) → SenseVoice STT → Gemma(LiteRT-LM) → supertonic TTS → AudioTrack
 *
 * 設計上の不変条件:
 * - start()/cancel() の本体は Dispatchers.Default(呼び出し元 CarVoiceInteractionSession の
 *   scope は Main。モデルロードやスレッド join を Main に置かない)
 * - start()/cancel() は [opMutex] で直列化し、[generation] でロック待ち中の追い越しを検出する
 *   (PTT 連打で「Voice Plate 非表示のままマイクが起動し続ける」事故の防止)
 * - capture.start() → render.start() の順(APM ハンドルは capture が所有)。停止は逆順で、
 *   render の join がタイムアウトしたら APM 破棄をスキップする
 * - stt-worker スレッドをブロックしない(onFinalResult からは launch で逃がすだけ)
 * - _state の更新は必ず update {}(複数スレッドから触るため CAS 必須)
 * - タイムアウトの activity は「確定発話・状態遷移・TTS 再生中」のみ。生の VAD 発火では
 *   リセットしない(車内ノイズでアイドルタイムアウトが無限延長されるのを防ぐ)
 */
class LocalAgentController(
    private val context: Context,
    private val stt: SpeechRecognizer = LocalAgentRuntime.stt,
    private val ttsPlayer: TtsPlayer = LocalAgentRuntime.ttsPlayer,
    private val ask: suspend (String) -> LocalToolBridge.LlmTurn = { prompt ->
        LocalToolBridge.toTurn(LocalAgentRuntime.llm.askMessage(prompt))
    },
    private val toolExecutor: DeviceToolExecutor = DeviceToolExecutor(emptyList()),
    private val sessionTimeoutPolicy: SessionTimeoutPolicy = SessionTimeoutPolicy(),
    private val onAutoTerminated: (DisconnectReason) -> Unit = {},
) : VoiceSessionController {

    private val _state = MutableStateFlow(ConversationSessionState())
    override val state: StateFlow<ConversationSessionState> = _state.asStateFlow()

    // scope は cancel() でも殺さない(単回使用オブジェクトとしてセッションと共に破棄される)。
    // scope.cancel() を追加すると watchdog 自身が呼ぶ cancel() が自分を殺して
    // onAutoTerminated が飛ばなくなる — ConversationController と同じ設計判断。
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val opMutex = Mutex()
    private val generation = AtomicInteger(0)

    private val bargeIn = BargeInDetector()
    private var watchdogJob: Job? = null

    @Volatile private var sessionStartedAtMs = 0L
    @Volatile private var lastActivityAtMs = 0L

    private var audioFocusRequest: AudioFocusRequest? = null
    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS ->
                // 通話・緊急など INTERACTION_EXCLUSIVE に負けた。マイクも止める。
                scope.launch { cancel(DisconnectReason.ERROR) }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK,
            -> ttsPlayer.cancel()
        }
    }

    private val capture = CapturePipeline(
        onCleanFrame = { buf ->
            // capture スレッド上。コピーして queue に渡すだけ(SenseVoiceRecognizer は
            // 配列参照を保持するため使い回し禁止)。
            val pcm = ShortArray(FRAME_SAMPLES)
            buf.position(0)
            for (i in 0 until FRAME_SAMPLES) pcm[i] = buf.getShort(i * 2)
            stt.acceptAudio(pcm, SAMPLE_RATE)
        },
    )
    private val render = RenderPipeline(
        engineHandle = { capture.engineHandle() },
        fillFrame = ttsPlayer::fillFrame,
    )

    override suspend fun start(): Unit = withContext(Dispatchers.Default) {
        val myGen = generation.get()
        opMutex.withLock {
            if (myGen != generation.get()) return@withLock // ロック待ち中に cancel() が確定
            if (_state.value.connection != ConnectionState.DISCONNECTED) return@withLock

            _state.update { it.copy(connection = ConnectionState.CONNECTING) }

            if (!LocalAgentRuntime.engineLoaded()) {
                fail("音響エンジン(.so)がこの ABI にありません(arm64-v8a のみ対応)")
                return@withLock
            }
            if (!LocalAgentRuntime.modelsAvailable()) {
                fail("モデル未配置です(scripts/fetch_*.sh で push してください)")
                return@withLock
            }
            if (!requestAudioFocus()) {
                fail("audio focus を取得できませんでした")
                return@withLock
            }

            ttsPlayer.cancel() // 前セッションの取り残しフレームを防御的に破棄

            LocalAgentRuntime.ensureInitialized() // 初回のみ数秒(WORKING 表示のまま待たせる)
            if (myGen != generation.get()) {
                // ロード待ちの間に cancel() が来ていた。開いたリソースを畳んで抜ける。
                abandonAudioFocus()
                _state.value = ConversationSessionState()
                return@withLock
            }
            LocalAgentRuntime.llm.resetConversation() // 会話履歴はセッションごとにリセット

            if (!capture.start()) {
                abandonAudioFocus()
                fail("マイクを初期化できませんでした")
                return@withLock
            }
            // ponytail: ストリーム遅延は暫定固定値。実測化(AudioTrack.getTimestamp ベース)は
            // 実機 AEC 評価(Tests A–E)とセットで見直す。
            capture.setStreamDelayMs(20)
            if (!render.start()) {
                capture.stop()
                abandonAudioFocus()
                fail("スピーカーを初期化できませんでした")
                return@withLock
            }

            stt.reset()
            stt.onFinalResult = { text -> onSttResult(text) }

            val now = SystemClock.elapsedRealtime()
            sessionStartedAtMs = now
            lastActivityAtMs = now
            _state.update {
                it.copy(
                    connection = ConnectionState.CONNECTED,
                    audioInput = AudioInputState.CAPTURING,
                    assistantTranscript = GREETING_TEXT,
                )
            }
            watchdogJob = scope.launch { watchdogLoop() }
            LocalAgentRuntime.sessionActive = true // onTrimMemory の解放をセッション中は抑止
            Log.i(TAG, "local agent session started (timeout=$sessionTimeoutPolicy)")
        }
    }

    override suspend fun cancel(reason: DisconnectReason): Unit = withContext(Dispatchers.Default) {
        // 先に世代を進めて、ロード待ち中の start() やに in-flight の推論結果を無効化する
        generation.incrementAndGet()
        safely("tts cancel") { ttsPlayer.cancel() }
        safely("llm cancelActive") { LocalAgentRuntime.cancelInference() }

        opMutex.withLock {
            if (_state.value == ConversationSessionState()) return@withLock // 冪等

            Log.i(TAG, "cancel($reason)")
            // watchdog 自身が cancel() を呼ぶ経路があるため、自分の Job は殺さない
            safely("watchdog cancel") {
                watchdogJob?.takeIf { it !== coroutineContext[Job] }?.cancel()
                watchdogJob = null
            }
            safely("stt detach") {
                stt.onFinalResult = null
                stt.reset()
            }
            // render → capture の順(capture が APM ハンドルを所有)。render の join が
            // タイムアウトした場合は破棄をスキップ(リーク < native クラッシュ)。
            val renderJoined = safelyOr("render stop", fallback = false) { render.stop() }
            safely("capture stop") { capture.stop(destroyEngine = renderJoined) }
            safely("abandon audio focus") { abandonAudioFocus() }
            LocalAgentRuntime.sessionActive = false
            _state.value = ConversationSessionState()
            if (reason != DisconnectReason.USER_CANCEL) {
                safely("onAutoTerminated") { onAutoTerminated(reason) }
            }
        }
    }

    /** stt-worker スレッド上。ここでは絶対にブロックしない(barge-in 検出が死ぬ)。 */
    private fun onSttResult(text: String) {
        if (!isAcceptableUtterance(text)) {
            Log.d(TAG, "fragment ignored: $text")
            return
        }
        scope.launch { onUtterance(text) }
    }

    /** 1 会話ターン。internal はテスト用(JVM/androidTest から直接駆動する)。 */
    internal suspend fun onUtterance(text: String) {
        val s = _state.value
        if (s.connection != ConnectionState.CONNECTED) return
        if (s.conversation == ConversationState.MODEL_PROCESSING) {
            Log.d(TAG, "utterance during THINKING ignored: $text") // v1 の割り切り(現ターン優先)
            return
        }
        val myGen = generation.get()
        touchActivity()
        _state.update {
            it.copy(
                conversation = ConversationState.MODEL_PROCESSING,
                userTranscript = text,
                audioOutput = AudioOutputState.IDLE,
            )
        }
        val turn = runCatching { ask(text) }.getOrElse { e ->
            Log.w(TAG, "llm ask failed", e)
            LocalToolBridge.LlmTurn(toolCall = null, replyText = FALLBACK_TEXT)
        }
        if (myGen != generation.get()) {
            Log.i(TAG, "late reply discarded") // 推論中に cancel() された(打ち切り or 破棄)
            return
        }
        val reply = if (turn.toolCall != null) {
            // issue #50: モデルのツールコールは必ず DeviceToolExecutor のパイプラインを通す。
            // 結果の読み上げは固定文(2 回目の LLM 往復を省き、パース漏れ救済経路とも整合)。
            Log.i(TAG, "tool call: ${turn.toolCall.name}(${turn.toolCall.argumentsJson})")
            _state.update { it.copy(conversation = ConversationState.TOOL_EXECUTING) }
            val result = toolExecutor.execute(turn.toolCall)
            if (myGen != generation.get()) return
            Log.i(TAG, "tool result: ${result.outcome} ${result.output}")
            toolConfirmationText(turn.toolCall.argumentsJson, result.outcome.name, result.output.optString("result"))
        } else {
            turn.replyText
        }
        touchActivity()
        _state.update {
            it.copy(
                conversation = ConversationState.IDLE,
                assistantTranscript = reply,
                audioOutput = AudioOutputState.PLAYING,
            )
        }
        ttsPlayer.speak(reply)
    }

    private suspend fun watchdogLoop() {
        while (coroutineContext.isActive) {
            delay(WATCHDOG_TICK_MS)
            if (!watchdogTick(SystemClock.elapsedRealtime())) return
        }
    }

    /**
     * 50ms tick: バージイン判定 + 再生完了検出 + タイムアウト。
     * @return false ならループ終了(auto-terminate を起動した)。
     */
    internal fun watchdogTick(nowMs: Long): Boolean {
        val s = _state.value
        val playing = s.audioOutput == AudioOutputState.PLAYING

        if (playing && stt.isSpeechActive()) {
            if (bargeIn.tick(true)) {
                Log.i(TAG, "barge-in: cancelling TTS")
                ttsPlayer.cancel()
                touchActivity()
                _state.update {
                    it.copy(
                        audioOutput = AudioOutputState.IDLE,
                        interruptionCount = it.interruptionCount + 1,
                    )
                }
            }
        } else {
            bargeIn.tick(false)
            if (playing && !ttsPlayer.isSpeaking()) {
                // 再生し切った → LISTENING へ
                touchActivity()
                _state.update { it.copy(audioOutput = AudioOutputState.IDLE) }
            }
        }

        // TTS 再生中と推論中は activity 扱い(応答が長くてもタイムアウトさせない)
        if (ttsPlayer.isSpeaking() || s.conversation == ConversationState.MODEL_PROCESSING) {
            touchActivity()
        }

        if (nowMs - sessionStartedAtMs >= sessionTimeoutPolicy.maxSessionDurationMs) {
            Log.w(TAG, "max session duration exceeded")
            scope.launch { cancel(DisconnectReason.MAX_DURATION_EXCEEDED) }
            return false
        }
        if (nowMs - lastActivityAtMs >= sessionTimeoutPolicy.idleTimeoutMs) {
            Log.w(TAG, "idle timeout exceeded")
            scope.launch { cancel(DisconnectReason.IDLE_TIMEOUT) }
            return false
        }
        return true
    }

    private fun touchActivity() {
        lastActivityAtMs = SystemClock.elapsedRealtime()
    }

    /** テスト専用: start() を通さずに状態を注入する(onUtterance/watchdogTick の単体駆動用)。 */
    internal fun seedForTest(state: ConversationSessionState, nowMs: Long = SystemClock.elapsedRealtime()) {
        _state.value = state
        sessionStartedAtMs = nowMs
        lastActivityAtMs = nowMs
    }

    /** 前提チェック失敗: FAILED + 理由を表示したまま残す(#47 のガード修正により次の PTT で再試行可)。 */
    private fun fail(message: String) {
        Log.w(TAG, "start failed: $message")
        _state.update {
            it.copy(connection = ConnectionState.FAILED, assistantTranscript = message)
        }
    }

    private fun requestAudioFocus(): Boolean {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener(focusListener)
            .build()
        audioFocusRequest = request
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        // 既存 ConversationController は結果を見ていないが、AAOS では通話中などに
        // INTERACTION_REJECT で普通に失敗する。失敗したら会話を始めない。
        return audioManager?.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonAudioFocus() {
        val request = audioFocusRequest ?: return
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        audioManager?.abandonAudioFocusRequest(request)
        audioFocusRequest = null
    }

    private inline fun safely(step: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            Log.w(TAG, "cancel step failed: $step", t)
        }
    }

    private inline fun safelyOr(step: String, fallback: Boolean, block: () -> Boolean): Boolean =
        try {
            block()
        } catch (t: Throwable) {
            Log.w(TAG, "cancel step failed: $step", t)
            fallback
        }

    companion object {
        private const val TAG = "LocalAgentController"
        private const val SAMPLE_RATE = 48000
        private const val FRAME_SAMPLES = 480
        private const val WATCHDOG_TICK_MS = 50L

        /** 既存 OpenAI モードと同じ固定挨拶(テキスト表示のみ、計画 §10-3)。 */
        const val GREETING_TEXT = "こんにちは、何か御用ですか"
        const val FALLBACK_TEXT = "すみません、うまく考えられませんでした。"
    }
}
