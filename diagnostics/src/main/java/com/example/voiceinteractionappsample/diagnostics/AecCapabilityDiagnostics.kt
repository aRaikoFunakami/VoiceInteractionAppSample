package com.example.voiceinteractionappsample.diagnostics

import org.webrtc.audio.JavaAudioDeviceModule

/**
 * 6節: 起動時に一度だけ記録する。「supported」を返しただけで採用完了とはしない — 実機試験
 * 結果は別途 device profile（5-2, docs/dev-plan.md）に記録する。ここは capability の読み取りのみ。
 */
data class AecCapability(
    val hardwareAecSupported: Boolean,
    val hardwareNsSupported: Boolean,
)

object AecCapabilityDiagnostics {
    fun read(): AecCapability = AecCapability(
        hardwareAecSupported = JavaAudioDeviceModule.isBuiltInAcousticEchoCancelerSupported(),
        hardwareNsSupported = JavaAudioDeviceModule.isBuiltInNoiseSuppressorSupported(),
    )
}
