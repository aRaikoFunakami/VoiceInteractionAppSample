package com.example.voiceinteractionappsample.realtime

import org.json.JSONObject

/**
 * `session.audio.input.noise_reduction` — OpenAI's server-side denoise pass that runs BEFORE
 * VAD sees the audio. Kept as its own type rather than folded into [RealtimeVadConfig]: that
 * class's kdoc deliberately keeps AEC/audio changes isolated from VAD tuning so causes stay
 * separable (9節) — noise_reduction is an audio-side knob, not a VAD one.
 *
 * `near_field` assumes a close mic (phone/headset held near the mouth). Switch to `far_field`
 * if the in-car mic ends up mounted away from the speaker.
 */
data class RealtimeNoiseReductionConfig(val type: String = "near_field") {
    fun toJson(): JSONObject = JSONObject().put("type", type)
}
