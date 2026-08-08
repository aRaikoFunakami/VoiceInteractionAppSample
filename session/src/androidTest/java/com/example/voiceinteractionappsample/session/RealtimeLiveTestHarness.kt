package com.example.voiceinteractionappsample.session

import android.content.Context
import com.example.voiceinteractionappsample.audio.AecMode
import com.example.voiceinteractionappsample.audio.WebRtcAudioEngine
import com.example.voiceinteractionappsample.realtime.MockRealtimeCredentialProvider
import com.example.voiceinteractionappsample.realtime.RealtimeConnection
import com.example.voiceinteractionappsample.realtime.RealtimeEvent
import com.example.voiceinteractionappsample.realtime.RealtimeEventCodec
import com.example.voiceinteractionappsample.realtime.RealtimeVadConfig
import com.example.voiceinteractionappsample.realtime.RealtimeWebRtcClient
import com.example.voiceinteractionappsample.realtime.WebRtcFactoryProvider
import java.time.Instant
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver

/**
 * Shared plumbing for the AEC live tests (5-2節, Test A〜E) and [FullDuplexAudioLiveTest] (4-1).
 * Not production code — :diagnostics/:session production wiring for a real ConversationController
 * is Phase 7, this only exists to drive the same connect/send/observe steps from several tests
 * without repeating them.
 */
object RealtimeLiveTestHarness {

    suspend fun connectWithMic(
        context: Context,
        ephemeralSecret: String,
        aecMode: AecMode = AecMode.AUTO,
        vadConfig: RealtimeVadConfig = RealtimeVadConfig(),
    ): RealtimeConnection {
        val audioDeviceModule = WebRtcAudioEngine.create(context, aecMode = aecMode)
        val factory = WebRtcFactoryProvider.create(context, audioDeviceModule)
        val audioSource = factory.createAudioSource(MediaConstraints())
        val localTrack = factory.createAudioTrack("mic0", audioSource)

        val credentialProvider = MockRealtimeCredentialProvider(
            clientSecret = ephemeralSecret,
            expiresAt = Instant.now().plusSeconds(120),
        )
        val client = RealtimeWebRtcClient(factory, credentialProvider)
        val connection = client.connect(NoOpObserver(), localTrack)
        withTimeout(10_000) { connection.events.awaitOpen() }
        connection.events.send(vadConfig.toSessionUpdateEvent())
        return connection
    }

    fun requestAssistantSpeech(connection: RealtimeConnection, instructions: String) {
        connection.events.send(
            JSONObject()
                .put("type", "response.create")
                .put("response", JSONObject().put("instructions", instructions))
                .toString()
        )
    }

    /**
     * Collects every event for [durationMs] — used to look for events that should NOT occur
     * (Test A/D: no `input_audio_buffer.speech_started` / user `conversation.item.created`
     * while nobody is actually speaking). The collecting coroutine is cancelled by
     * [withTimeoutOrNull] after the window; [events] is a local var mutated inside the collect
     * lambda, so whatever arrived before cancellation is kept — unlike relying on the
     * (discarded-on-cancel) return value of a terminal flow operator like toList().
     */
    suspend fun collectEventsFor(connection: RealtimeConnection, durationMs: Long): List<RealtimeEvent> =
        collectTimedEventsFor(connection, durationMs).map { it.second }

    /** Same as [collectEventsFor] but keeps each event's arrival time (ms since collection started) — Test B/C need to tell "did speech_started happen before response.done" apart, not just whether both happened somewhere. */
    suspend fun collectTimedEventsFor(connection: RealtimeConnection, durationMs: Long): List<Pair<Long, RealtimeEvent>> {
        val start = android.os.SystemClock.elapsedRealtime()
        val events = mutableListOf<Pair<Long, RealtimeEvent>>()
        withTimeoutOrNull(durationMs) {
            connection.events.incoming().collect { json ->
                val elapsed = android.os.SystemClock.elapsedRealtime() - start
                events.add(elapsed to RealtimeEventCodec.decode(json))
            }
        }
        return events
    }
}

private open class NoOpObserver : PeerConnection.Observer {
    override fun onSignalingChange(newState: PeerConnection.SignalingState?) = Unit
    override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) = Unit
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
}
