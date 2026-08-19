package com.example.voiceinteractionappsample.session

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.SystemClock
import android.util.Log
import com.example.voiceinteractionappsample.audio.AecMode
import com.example.voiceinteractionappsample.audio.WebRtcAudioEngine
import com.example.voiceinteractionappsample.realtime.ConversationLanguage
import com.example.voiceinteractionappsample.realtime.RealtimeConnection
import com.example.voiceinteractionappsample.realtime.RealtimeCredentialProvider
import com.example.voiceinteractionappsample.realtime.RealtimeEventCodec
import com.example.voiceinteractionappsample.realtime.RealtimeNoiseReductionConfig
import com.example.voiceinteractionappsample.realtime.RealtimeUsageCost
import com.example.voiceinteractionappsample.realtime.RealtimeVadConfig
import com.example.voiceinteractionappsample.realtime.RealtimeEvent
import com.example.voiceinteractionappsample.realtime.RealtimeWebRtcClient
import com.example.voiceinteractionappsample.realtime.WebRtcFactoryProvider
import com.example.voiceinteractionappsample.tools.DeviceToolExecutor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.audio.JavaAudioDeviceModule

/** Why [ConversationController.cancel] was called — recorded for diagnostics (21節). */
enum class DisconnectReason { USER_CANCEL, NETWORK_LOST, HIDE_COMPLETE, ERROR, IDLE_TIMEOUT, MAX_DURATION_EXCEEDED }

/**
 * Owns the Realtime connection lifecycle (1節, 17節, 18節): connection state, microphone
 * state, playback state, tool state, cancel処理. Composes :realtime + :audio; a
 * [CarVoiceInteractionSession] (in :via) drives this and hides the Voice Plate only AFTER
 * [cancel] returns — Voice Plate hide itself is a :via concern, not :session's (17節: `onHide()`
 * と完全な conversation termination を同一視しない — see :via's kdoc for that half).
 *
 * [cancel] is the single teardown path (18節: どの状態からもcancelできる) and is idempotent —
 * every step is independently best-effort (7-2節: microphone/remote trackのどちらかだけ残る
 * 失敗を許容しない), so a failure releasing one resource never skips releasing the rest.
 */
class ConversationController(
    private val context: Context,
    private val credentialProvider: RealtimeCredentialProvider,
    // issue #43: lets callers point WebRTC SDP exchange at a local Realtime-compatible server.
    private val realtimeCallsUrl: String = RealtimeWebRtcClient.DEFAULT_REALTIME_CALLS_URL,
    private val vadConfig: RealtimeVadConfig = RealtimeVadConfig(),
    private val noiseReductionConfig: RealtimeNoiseReductionConfig = RealtimeNoiseReductionConfig(),
    private val aecMode: AecMode = AecMode.AUTO,
    private val reconnectPolicy: ReconnectPolicy = ReconnectPolicy(),
    private val sessionTimeoutPolicy: SessionTimeoutPolicy = SessionTimeoutPolicy(),
    /**
     * 実機で発見（"ツールを呼び出せない"）: :tools側のスキーマ・実行パイプライン・
     * [RealtimeToolBridge]は全部Phase 8-9で作って個別にテスト済みだったのに、
     * ConversationControllerが誰も配線していなかった — session.updateの`tools`が常に空配列
     * のままサーバーに送られていた（実機ログで確認: `"tools":[]`）。デフォルトは空のまま
     * （既存の呼び出し元・テストを壊さない）— 実際にtoolを使わせたい呼び出し側が渡す。
     */
    private val toolSchemas: JSONArray = JSONArray(),
    private val toolExecutor: DeviceToolExecutor = DeviceToolExecutor(emptyList()),
    // 会話言語(instructions・STT言語ヒント・固定挨拶に反映)。設定画面の Language に従う。
    private val language: ConversationLanguage = ConversationLanguage.JA,
    /**
     * Called when [cancel] runs for any reason OTHER than [DisconnectReason.USER_CANCEL] —
     * i.e. the connection tore itself down (idle timeout, max duration, ICE failure) without
     * anyone telling the UI. Found live: the watchdog was correctly cancelling the RTC/mic
     * (verified via AudioRecord standby state and logs) the whole time, but nothing told
     * [CarVoiceInteractionSession] to hide, so the Voice Plate kept showing a stale
     * "LISTENING" indefinitely — looked exactly like the timeout wasn't working at all.
     * Invoked from [scope] (background dispatcher) — the caller must hop to Main itself if it
     * touches UI (that's exactly what [CarVoiceInteractionSession] does here).
     */
    private val onAutoTerminated: (DisconnectReason) -> Unit = {},
) : PeerConnection.Observer, VoiceSessionController {

    private var reconnectAttempt = 0
    private var watchdogJob: Job? = null

    // SystemClock.elapsedRealtime() — monotonic, unaffected by wall-clock adjustments.
    @Volatile private var sessionStartedAtMs = 0L
    @Volatile private var lastActivityAtMs = 0L

    private val _state = MutableStateFlow(ConversationSessionState())
    override val state: StateFlow<ConversationSessionState> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob())
    private var eventCollectionJob: Job? = null

    private var audioDeviceModule: JavaAudioDeviceModule? = null
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var audioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null
    private var connection: RealtimeConnection? = null
    private var audioFocusRequest: AudioFocusRequest? = null

    override suspend fun start() {
        if (_state.value.connection != ConnectionState.DISCONNECTED) return // already starting/started

        _state.update { it.copy(connection = ConnectionState.CONNECTING) }

        // issue #61: モデル読み込み等の前に権限を確認する(:audio が宣言する RECORD_AUDIO は
        // LOCAL_AGENT と共有のパーミッション。VoiceInteractionSession は Activity ではないため
        // MicPermissionGate 経由でトランポリン Activity からダイアログを出す)。
        if (!MicPermissionGate.request(context)) {
            _state.update {
                it.copy(connection = ConnectionState.FAILED, audioInput = AudioInputState.ERROR)
            }
            cancel(DisconnectReason.ERROR)
            return
        }

        requestAudioFocus()

        // 実機で発見: AAOS Emulatorのaudioserverがセッション確立の瞬間にクラッシュ/自己再起動
        // することがあり、その巻き添えでAudioTrack初期化が失敗する（"AudioFlinger could not
        // create track"）。WebRtcAudioEngineは元々onRecordError/onPlayoutErrorを持っていたが
        // 誰も配線しておらず、失敗しても内部ログにしか残らずUIは平然とLISTENINGのままだった
        // ("話しかけたが何もおきない"の正体) — ここで拾ってERROR状態に反映する。
        val adm = WebRtcAudioEngine.create(
            context,
            aecMode = aecMode,
            onRecordError = { message ->
                Log.e(TAG, "audio record error: $message")
                _state.update { it.copy(audioInput = AudioInputState.ERROR) }
            },
            onPlayoutError = { message ->
                Log.e(TAG, "audio playout error: $message")
                _state.update { it.copy(audioOutput = AudioOutputState.ERROR) }
            },
        )
        audioDeviceModule = adm
        val factory = WebRtcFactoryProvider.create(context, adm)
        peerConnectionFactory = factory
        val source = factory.createAudioSource(MediaConstraints())
        audioSource = source
        val track = factory.createAudioTrack("mic0", source)
        localAudioTrack = track

        val client = RealtimeWebRtcClient(factory, credentialProvider, realtimeCallsUrl)
        val newConnection = client.connect(this, track)
        connection = newConnection
        newConnection.events.send(buildSessionUpdateEvent())
        // 初回挨拶を音声でも出す。server VAD はユーザーが話すまで応答を生成しないため、
        // response.create で初回応答を明示的にトリガーする。per-response instructions なので
        // session.update 側の人格・tools とは独立。LLM のため文言の完全一致は保証されないが、
        // assistantTranscript は response.audio_transcript.done の実発話で上書きされる。
        newConnection.events.send(
            JSONObject()
                .put("type", "response.create")
                .put(
                    "response",
                    JSONObject().put(
                        "instructions",
                        "Greet the user with exactly: \"${greetingText(language)}\". Say nothing else.",
                    ),
                )
                .toString(),
        )

        _state.update {
            it.copy(
                connection = ConnectionState.CONNECTED,
                audioInput = AudioInputState.CAPTURING,
                // ユーザー要望: マイクが受付状態になった時点で画面に固定挨拶を出す。実際の
                // モデル応答が来ればすぐ上書きされる — 同じ表示欄を再利用しているだけ。
                assistantTranscript = greetingText(language),
            )
        }

        val now = SystemClock.elapsedRealtime()
        sessionStartedAtMs = now
        lastActivityAtMs = now

        eventCollectionJob = scope.launch {
            newConnection.events.incoming().collect { json ->
                // Debugレベルで生イベントを残す — このセッション内だけでも複数回、
                // 「実際に何が起きているか」の切り分けにログの実物が必要だった
                // (audioserverクラッシュ、ICEハング、session.updateドロップ、今回の割り込み
                // 頻発など)。毎回一時的に足すより最初から残しておく方が早い。
                Log.d(TAG, "event: $json")
                onRealtimeEvent(JSONObject(json))
            }
        }
        Log.i(TAG, "start(): watchdog launching, idleTimeoutMs=${sessionTimeoutPolicy.idleTimeoutMs} maxSessionDurationMs=${sessionTimeoutPolicy.maxSessionDurationMs}")
        watchdogJob = scope.launch { runTimeoutWatchdog() }
    }

    /**
     * 26節「conversation idle timeout」への対応 — 課金リスクの歯止め（ユーザーからの
     * フィードバックで追加）。アイドルタイムアウトと最大セッション時間の両方を見る。
     */
    private suspend fun runTimeoutWatchdog() {
        while (coroutineContext.isActive) {
            delay(WATCHDOG_CHECK_INTERVAL_MS)
            val now = SystemClock.elapsedRealtime()
            when {
                now - sessionStartedAtMs >= sessionTimeoutPolicy.maxSessionDurationMs -> {
                    Log.w(TAG, "max session duration exceeded, forcing cancel")
                    cancel(DisconnectReason.MAX_DURATION_EXCEEDED)
                    return
                }
                now - lastActivityAtMs >= sessionTimeoutPolicy.idleTimeoutMs -> {
                    Log.w(TAG, "idle timeout exceeded, forcing cancel")
                    cancel(DisconnectReason.IDLE_TIMEOUT)
                    return
                }
            }
        }
    }

    private suspend fun onRealtimeEvent(event: JSONObject) {
        lastActivityAtMs = SystemClock.elapsedRealtime()
        when (val type = event.optString("type", "unknown")) {
            "output_audio_buffer.started" -> _state.update { it.copy(audioOutput = AudioOutputState.PLAYING) }
            "output_audio_buffer.stopped" ->
                _state.update { it.copy(audioOutput = AudioOutputState.IDLE, conversation = ConversationState.IDLE) }
            "response.done" -> onResponseDone(event)
            "input_audio_buffer.speech_started" ->
                _state.update { it.copy(conversation = ConversationState.USER_SPEAKING) }
            "input_audio_buffer.speech_stopped" ->
                _state.update { it.copy(conversation = ConversationState.MODEL_PROCESSING) }
            // ユーザー要望4: 音声認識されたユーザーの発話をデバッグ表示。session.updateで
            // audio.input.transcriptionを有効にしないとこのイベント自体来ない。
            "conversation.item.input_audio_transcription.completed" ->
                _state.update { it.copy(userTranscript = event.optString("transcript")) }
            // ユーザー要望5: AIの発話内容(音声に同期したテキスト)をデバッグ表示。イベント名は
            // APIバージョンにより response.audio_transcript.done / response.output_audio_transcript.done
            // の両方があり得るため両対応しておく（実機で実際に来た方を確認済み — 下記参照）。
            "response.audio_transcript.done", "response.output_audio_transcript.done" ->
                _state.update { it.copy(assistantTranscript = event.optString("transcript")) }
            // ユーザー要望・実機で発見: サーバーVADがAI自身の発話中に "input_audio_buffer.
            // speech_started" を検出すると、即座にoutput_audio_bufferがclearされ応答が
            // 打ち切られる(conversation.item.truncated)。実機ログで頻発を確認 — ユーザーが
            // 何も言っていないのに毎回打ち切られる症状で、エコーキャンセラーがAI自身の声を
            // 拾って誤って割り込みと判定している疑い。ログ+画面に必ず残す。
            "output_audio_buffer.cleared" -> {
                Log.w(TAG, "assistant response interrupted (barge-in detected by server VAD, or echo)")
                _state.update { it.copy(interruptionCount = it.interruptionCount + 1) }
            }
            // 実機で発見（"ツールを呼び出せない"）: :toolsのスキーマ/実行パイプライン/
            // RealtimeToolBridgeはPhase 8-9で作って個別にテスト済みだったが、
            // ここが配線されていなかったためsession.updateの`tools`が常に空で、モデルは
            // ツールの存在自体を知らなかった（実機ログで`"tools":[]`を確認）。
            "response.function_call_arguments.done" -> handleFunctionCall(event)
            else -> Unit
        }
    }

    /** ユーザー要望: トークン数と推定課金額(USD)をデバッグ表示。usageはresponse.doneにだけ乗る。 */
    private fun onResponseDone(event: JSONObject) {
        val usageJson = event.optJSONObject("response")?.optJSONObject("usage")
        val usage = RealtimeUsageCost.parseUsage(usageJson)
        _state.update {
            it.copy(
                audioOutput = AudioOutputState.IDLE,
                conversation = ConversationState.IDLE,
                totalTokens = it.totalTokens + (usage?.totalTokens ?: 0),
                totalCostUsd = it.totalCostUsd + (usage?.let(RealtimeUsageCost::estimateCostUsd) ?: 0.0),
            )
        }
    }

    private suspend fun handleFunctionCall(event: JSONObject) {
        val call = RealtimeToolBridge.decodeFunctionCall(RealtimeEvent(event.optString("type"), event)) ?: return
        Log.i(TAG, "tool call: ${call.name}(${call.argumentsJson})")
        val result = toolExecutor.execute(call)
        Log.i(TAG, "tool result: ${result.outcome} ${result.output}")
        val activeConnection = connection ?: return
        RealtimeToolBridge.encodeFunctionCallOutput(result).forEach { activeConnection.events.send(it) }
    }

    /** VAD調整 + 車載アシスタント人格 + 日本語文字起こし + tool登録を1つのsession.updateにまとめる。 */
    private fun buildSessionUpdateEvent(): String =
        RealtimeEventCodec.encodeSessionUpdate(
            JSONObject()
                .put("type", "realtime")
                .put("instructions", carAssistantInstructions(language))
                .put("tools", toolSchemas)
                .put(
                    "audio",
                    JSONObject().put(
                        "input",
                        JSONObject()
                            .put("turn_detection", vadConfig.toTurnDetectionJson())
                            // 車内雑音がVADに"発話"として誤検知される対策 — VADより前段で
                            // サーバー側denoiseをかける（[RealtimeNoiseReductionConfig]参照）。
                            .put("noise_reduction", noiseReductionConfig.toJson())
                            // ユーザー要望2: 英語しか認識しない対策 — STTに言語をヒントする。
                            .put(
                                "transcription",
                                JSONObject().put("model", "whisper-1").put("language", language.code),
                            ),
                    ),
                )
        )

    /**
     * 18節: 全conversation状態から呼べる。冪等 — 既に切断済みなら何もしない。各ステップは
     * best-effort（1つ失敗しても残りは実行する）。順序は7-1節の通り: response cancel ->
     * DataChannel close -> PeerConnection close -> AudioDeviceModule release -> audio focus
     * release。Voice Plate hideはここに含めない（:viaの責務）。
     */
    // デフォルト引数は VoiceSessionController 側で宣言済み(override では再宣言できない)。
    override suspend fun cancel(reason: DisconnectReason) {
        if (_state.value.connection == ConnectionState.DISCONNECTED && connection == null) return

        Log.i(TAG, "cancel: reason=$reason")

        val activeConnection = connection
        safely("response.cancel") {
            activeConnection?.events?.send(JSONObject().put("type", "response.cancel").toString())
        }
        safely("eventCollectionJob.cancel") { eventCollectionJob?.cancel() }
        safely("watchdogJob.cancel") { watchdogJob?.takeIf { it !== coroutineContext[Job] }?.cancel() }
        safely("events.close (DataChannel)") { activeConnection?.events?.close() }
        safely("peerConnection.close") { activeConnection?.peerConnection?.close() }
        safely("localAudioTrack.dispose") { localAudioTrack?.dispose() }
        safely("audioSource.dispose") { audioSource?.dispose() }
        safely("peerConnectionFactory.dispose") { peerConnectionFactory?.dispose() }
        safely("audioDeviceModule.release") { audioDeviceModule?.release() }
        safely("abandon audio focus") { abandonAudioFocus() }

        connection = null
        localAudioTrack = null
        audioSource = null
        peerConnectionFactory = null
        audioDeviceModule = null
        eventCollectionJob = null
        watchdogJob = null

        _state.value = ConversationSessionState() // all-idle/disconnected default

        if (reason != DisconnectReason.USER_CANCEL) {
            safely("onAutoTerminated callback") { onAutoTerminated(reason) }
        }
    }

    private suspend inline fun safely(step: String, crossinline block: suspend () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            Log.w(TAG, "cancel step failed (continuing): $step", e)
        }
    }

    private fun requestAudioFocus() {
        // 8節: 正確なaudio focus種別（duck/pause方針含む）は未確定（26節）。GAIN +
        // USAGE_ASSISTANT を暫定値として使う — 実車評価で見直す前提。
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attributes)
            .build()
        audioFocusRequest = request
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        audioManager?.requestAudioFocus(request)
    }

    private fun abandonAudioFocus() {
        val request = audioFocusRequest ?: return
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        audioManager?.abandonAudioFocusRequest(request)
        audioFocusRequest = null
    }

    // PeerConnection.Observer — feeds ConnectionState from real ICE state (7-1節, 7-3節).
    override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) {
        // 実機で発見: SDP交渉(シグナリング)自体は成功したのに、その後ICE/DTLSが繋がらず
        // Realtimeイベントが1つも来ない、というケースがログから一切見えなかった —
        // 「何も起きなかった」ことの原因究明が状況証拠頼みになっていた。ここに1行足すだけで
        // 次回はCHECKINGのまま固まっている、等が直接見える。
        Log.i(TAG, "ICE state: $newState")
        when (newState) {
            PeerConnection.IceConnectionState.CONNECTED,
            PeerConnection.IceConnectionState.COMPLETED -> {
                reconnectAttempt = 0 // recovered
                _state.update { it.copy(connection = ConnectionState.CONNECTED) }
            }
            PeerConnection.IceConnectionState.DISCONNECTED -> {
                reconnectAttempt++
                if (reconnectPolicy.isExhausted(reconnectAttempt)) {
                    failAndCleanUp()
                } else {
                    // Re-negotiating a fresh offer isn't implemented here — this only bounds
                    // how long a dangling connection stays around before forcing cleanup
                    // (7-3節: 切断時にaudio captureを残さない). ReconnectPolicy's backoff is the
                    // documented retry schedule a future reconnect implementation should use.
                    _state.update { it.copy(connection = ConnectionState.RECONNECTING) }
                }
            }
            PeerConnection.IceConnectionState.FAILED -> failAndCleanUp()
            PeerConnection.IceConnectionState.CLOSED -> _state.update { it.copy(connection = ConnectionState.DISCONNECTED) }
            PeerConnection.IceConnectionState.CHECKING,
            PeerConnection.IceConnectionState.NEW -> _state.update { it.copy(connection = ConnectionState.CONNECTING) }
            else -> Unit
        }
    }

    /** 7-3節: FAILEDはaudio captureを残さない — 即座にcancel()と同じ完全クリーンアップを走らせる。 */
    private fun failAndCleanUp() {
        _state.update { it.copy(connection = ConnectionState.FAILED) }
        scope.launch { cancel(DisconnectReason.NETWORK_LOST) }
    }

    override fun onSignalingChange(newState: PeerConnection.SignalingState?) = Unit
    override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
    override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState?) = Unit
    override fun onIceCandidate(candidate: IceCandidate?) = Unit
    override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) = Unit
    override fun onAddStream(stream: MediaStream?) = Unit
    override fun onRemoveStream(stream: MediaStream?) = Unit
    override fun onDataChannel(dataChannel: DataChannel?) = Unit
    override fun onRenegotiationNeeded() = Unit
    override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out MediaStream>?) = Unit
    override fun onTrack(transceiver: RtpTransceiver?) = Unit

    private companion object {
        const val TAG = "ConversationController"
        // 10秒アイドルタイムアウトに対して検出遅延が相対的に大きくならないよう短めにする。
        const val WATCHDOG_CHECK_INTERVAL_MS = 2_000L

        fun greetingText(language: ConversationLanguage): String = when (language) {
            ConversationLanguage.JA -> "こんにちは、何か御用ですか"
            ConversationLanguage.EN -> "Hello, how can I help you?"
        }

        // ユーザー要望3: 車のAIアシスタントとして振る舞わせる。ユーザー要望2の「日本語で
        // 話す」もここで指示する — Realtime APIには出力言語を直接指定するフィールドが無く、
        // instructionsで指示するのが標準的なやり方。言語は設定に従って切り替える。
        fun carAssistantInstructions(language: ConversationLanguage): String = when (language) {
            ConversationLanguage.JA -> """
あなたは自動車に搭載されている音声AIアシスタントです。運転者や同乗者の質問や指示に、
必ず日本語で、簡潔かつ分かりやすく答えてください。運転の妨げにならないよう、長い説明は
避け、要点だけを話してください。
            """
            ConversationLanguage.EN -> """
You are a voice AI assistant built into a car. Always answer the driver's and passengers'
questions and requests in English, concisely and clearly. Avoid long explanations so you
don't distract from driving — stick to the essentials.
            """
        }
    }
}
