package com.example.voiceinteractionappsample.audio

import android.content.Context
import android.media.MediaRecorder
import org.webrtc.audio.JavaAudioDeviceModule

/**
 * Which acoustic echo canceler / noise suppressor implementation to use (6節, 5-1節).
 *
 * - [AUTO]: don't override anything — JavaAudioDeviceModule's own default (use hardware
 *   AEC/NS when the device reports support, per [com.example.voiceinteractionappsample.diagnostics.AecCapabilityDiagnostics]).
 *   Behavior must not vary per product/build (5-1節: "AUTOの挙動を製品ごとに変化させない").
 * - [HARDWARE]: force the device's hardware AEC/NS on, even if not otherwise selected.
 * - [WEBRTC]: force hardware AEC/NS off, so WebRTC's own software AEC3 handles it instead.
 *
 * Selectable only in diagnostic builds (5-1節) — production wiring (:session, Phase 7+)
 * passes one fixed, evaluated mode; it does not expose this choice in end-user UI.
 */
enum class AecMode { AUTO, HARDWARE, WEBRTC }

/**
 * Builds the [JavaAudioDeviceModule] (6節). AEC/NS capability diagnostics live in
 * :diagnostics (2-3) — this class only constructs the module and reports AudioRecord/
 * AudioTrack init/runtime errors through the given callbacks.
 */
object WebRtcAudioEngine {
    fun create(
        context: Context,
        aecMode: AecMode = AecMode.AUTO,
        onRecordError: (String) -> Unit = {},
        onPlayoutError: (String) -> Unit = {},
    ): JavaAudioDeviceModule {
        val builder = JavaAudioDeviceModule.builder(context)
            .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
        when (aecMode) {
            AecMode.AUTO -> Unit // leave JavaAudioDeviceModule's own default in place
            AecMode.HARDWARE -> builder
                .setUseHardwareAcousticEchoCanceler(true)
                .setUseHardwareNoiseSuppressor(true)
            AecMode.WEBRTC -> builder
                .setUseHardwareAcousticEchoCanceler(false)
                .setUseHardwareNoiseSuppressor(false)
        }
        return builder
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
}
