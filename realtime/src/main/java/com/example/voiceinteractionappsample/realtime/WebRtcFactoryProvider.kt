package com.example.voiceinteractionappsample.realtime

import android.content.Context
import org.webrtc.PeerConnectionFactory
import org.webrtc.audio.AudioDeviceModule

/**
 * Creates a [PeerConnectionFactory] only. SDP offer/answer, DataChannel handling and the
 * OpenAI Realtime event codec belong to RealtimeWebRtcClient (4節) — added in Phase 3 once
 * there is a real connection to negotiate.
 *
 * The [AudioDeviceModule] is supplied by the caller. :session composes this module with
 * :audio's WebRtcAudioEngine (Phase 2-2) — :realtime has no dependency on :audio and must
 * not construct one itself.
 */
object WebRtcFactoryProvider {
    fun create(context: Context, audioDeviceModule: AudioDeviceModule? = null): PeerConnectionFactory {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                .createInitializationOptions()
        )
        val builder = PeerConnectionFactory.builder()
        if (audioDeviceModule != null) {
            builder.setAudioDeviceModule(audioDeviceModule)
        }
        return builder.createPeerConnectionFactory()
    }
}
