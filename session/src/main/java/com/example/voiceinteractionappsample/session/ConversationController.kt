package com.example.voiceinteractionappsample.session

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
enum class DisconnectReason { USER_CANCEL, NETWORK_LOST, HIDE_COMPLETE, ERROR }

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
) : PeerConnection.Observer {

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

        val adm = WebRtcAudioEngine.create(context, aecMode = aecMode)
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

        eventCollectionJob = scope.launch {
            newConnection.events.incoming().collect { json ->
                onRealtimeEvent(JSONObject(json).optString("type", "unknown"))
            }
        }
    }

    private fun onRealtimeEvent(type: String) {
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

        _state.value = ConversationSessionState() // all-idle/disconnected default
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

    // PeerConnection.Observer — feeds ConnectionState from real ICE state (7-1節).
    override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) {
        val mapped = when (newState) {
            PeerConnection.IceConnectionState.CONNECTED,
            PeerConnection.IceConnectionState.COMPLETED -> ConnectionState.CONNECTED
            PeerConnection.IceConnectionState.DISCONNECTED -> ConnectionState.RECONNECTING
            PeerConnection.IceConnectionState.FAILED -> ConnectionState.FAILED
            PeerConnection.IceConnectionState.CLOSED -> ConnectionState.DISCONNECTED
            PeerConnection.IceConnectionState.CHECKING,
            PeerConnection.IceConnectionState.NEW -> ConnectionState.CONNECTING
            else -> return
        }
        _state.update { it.copy(connection = mapped) }
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
    }
}
