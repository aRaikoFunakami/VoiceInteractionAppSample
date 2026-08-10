package com.example.voiceinteractionappsample.session

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.SystemClock
import android.util.Log
import com.example.voiceinteractionappsample.audio.AecMode
import com.example.voiceinteractionappsample.audio.WebRtcAudioEngine
import com.example.voiceinteractionappsample.realtime.RealtimeConnection
import com.example.voiceinteractionappsample.realtime.RealtimeCredentialProvider
import com.example.voiceinteractionappsample.realtime.RealtimeVadConfig
import com.example.voiceinteractionappsample.realtime.RealtimeWebRtcClient
import com.example.voiceinteractionappsample.realtime.WebRtcFactoryProvider
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
    private val vadConfig: RealtimeVadConfig = RealtimeVadConfig(),
    private val aecMode: AecMode = AecMode.AUTO,
    private val reconnectPolicy: ReconnectPolicy = ReconnectPolicy(),
    private val sessionTimeoutPolicy: SessionTimeoutPolicy = SessionTimeoutPolicy(),
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
) : PeerConnection.Observer {

    private var reconnectAttempt = 0
    private var watchdogJob: Job? = null

    // SystemClock.elapsedRealtime() — monotonic, unaffected by wall-clock adjustments.
    @Volatile private var sessionStartedAtMs = 0L
    @Volatile private var lastActivityAtMs = 0L

    private val _state = MutableStateFlow(ConversationSessionState())
    val state: StateFlow<ConversationSessionState> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob())
    private var eventCollectionJob: Job? = null

    private var audioDeviceModule: JavaAudioDeviceModule? = null
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var audioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null
    private var connection: RealtimeConnection? = null
    private var audioFocusRequest: AudioFocusRequest? = null

    suspend fun start() {
        if (_state.value.connection != ConnectionState.DISCONNECTED) return // already starting/started

        _state.update { it.copy(connection = ConnectionState.CONNECTING) }
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

        val client = RealtimeWebRtcClient(factory, credentialProvider)
        val newConnection = client.connect(this, track)
        connection = newConnection
        newConnection.events.send(vadConfig.toSessionUpdateEvent())

        _state.update {
            it.copy(
                connection = ConnectionState.CONNECTED,
                audioInput = AudioInputState.CAPTURING,
            )
        }

        val now = SystemClock.elapsedRealtime()
        sessionStartedAtMs = now
        lastActivityAtMs = now

        eventCollectionJob = scope.launch {
            newConnection.events.incoming().collect { json ->
                onRealtimeEvent(JSONObject(json).optString("type", "unknown"))
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

    private fun onRealtimeEvent(type: String) {
        lastActivityAtMs = SystemClock.elapsedRealtime()
        when (type) {
            "output_audio_buffer.started" -> _state.update { it.copy(audioOutput = AudioOutputState.PLAYING) }
            "output_audio_buffer.stopped", "response.done" ->
                _state.update { it.copy(audioOutput = AudioOutputState.IDLE, conversation = ConversationState.IDLE) }
            "input_audio_buffer.speech_started" ->
                _state.update { it.copy(conversation = ConversationState.USER_SPEAKING) }
            "input_audio_buffer.speech_stopped" ->
                _state.update { it.copy(conversation = ConversationState.MODEL_PROCESSING) }
        }
    }

    /**
     * 18節: 全conversation状態から呼べる。冪等 — 既に切断済みなら何もしない。各ステップは
     * best-effort（1つ失敗しても残りは実行する）。順序は7-1節の通り: response cancel ->
     * DataChannel close -> PeerConnection close -> AudioDeviceModule release -> audio focus
     * release。Voice Plate hideはここに含めない（:viaの責務）。
     */
    suspend fun cancel(reason: DisconnectReason = DisconnectReason.USER_CANCEL) {
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

    private inline fun safely(step: String, block: () -> Unit) {
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
    }
}
