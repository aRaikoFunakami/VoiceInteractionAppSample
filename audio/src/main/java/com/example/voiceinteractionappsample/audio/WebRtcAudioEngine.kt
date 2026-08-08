package com.example.voiceinteractionappsample.audio

import android.content.Context
import android.media.MediaRecorder
import org.webrtc.audio.JavaAudioDeviceModule

/**
 * Builds the [JavaAudioDeviceModule] (6節). AEC/NS capability diagnostics live in
 * :diagnostics (2-3) — this class only constructs the module and reports AudioRecord/
 * AudioTrack init/runtime errors through the given callbacks.
 *
 * AEC/NS themselves are left at JavaAudioDeviceModule's defaults here — the AUTO/HARDWARE/
 * WEBRTC diagnostic-build switch is Phase 5 (5-1), once there's something to compare against.
 */
object WebRtcAudioEngine {
    fun create(
        context: Context,
        onRecordError: (String) -> Unit = {},
        onPlayoutError: (String) -> Unit = {},
    ): JavaAudioDeviceModule =
        JavaAudioDeviceModule.builder(context)
            .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            .setAudioRecordErrorCallback(object : JavaAudioDeviceModule.AudioRecordErrorCallback {
                override fun onWebRtcAudioRecordInitError(errorMessage: String?) {
                    onRecordError("init: $errorMessage")
                }

                override fun onWebRtcAudioRecordStartError(
                    errorCode: JavaAudioDeviceModule.AudioRecordStartErrorCode?,
                    errorMessage: String?,
                ) {
                    onRecordError("start($errorCode): $errorMessage")
                }

                override fun onWebRtcAudioRecordError(errorMessage: String?) {
                    onRecordError("runtime: $errorMessage")
                }
            })
            .setAudioTrackErrorCallback(object : JavaAudioDeviceModule.AudioTrackErrorCallback {
                override fun onWebRtcAudioTrackInitError(errorMessage: String?) {
                    onPlayoutError("init: $errorMessage")
                }

                override fun onWebRtcAudioTrackStartError(
                    errorCode: JavaAudioDeviceModule.AudioTrackStartErrorCode?,
                    errorMessage: String?,
                ) {
                    onPlayoutError("start($errorCode): $errorMessage")
                }

                override fun onWebRtcAudioTrackError(errorMessage: String?) {
                    onPlayoutError("runtime: $errorMessage")
                }
            })
            .createAudioDeviceModule()
}
