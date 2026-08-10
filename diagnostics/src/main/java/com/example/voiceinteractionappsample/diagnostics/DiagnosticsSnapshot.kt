package com.example.voiceinteractionappsample.diagnostics

/** 21節: 起動時診断の全項目。 */
data class DiagnosticsSnapshot(
    val buildFingerprint: String,
    val apiLevel: Int,
    val androidRelease: String,
    val activeVoiceInteractionService: String?,
    val isRoleAssistantHeld: Boolean,
    val inputAudioDevices: List<String>,
    val outputAudioDevices: List<String>,
    val inputSampleRate: String,
    val outputSampleRate: String,
    val hardwareAecSupported: Boolean,
    val hardwareNsSupported: Boolean,
    val selectedAecMode: String,
    val libwebrtcLibrary: String,
    val supportedAbis: List<String>,
    val backendReachable: Boolean?,
    val connectionState: String,
    val iceState: String?,
    val selectedCandidatePair: String?,
    val browserActionViewHandlerAvailable: Boolean,
)
