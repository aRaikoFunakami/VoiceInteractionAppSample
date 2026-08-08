package com.example.voiceinteractionappsample.realtime

import org.json.JSONObject

/**
 * Server VAD tuning (9節, 6-1節). Semantic VAD is out of scope — changing AEC, audio routing,
 * and VAD termination detection at the same time makes it impossible to isolate causes (9節).
 *
 * Defaults here are OpenAI's OWN observed session defaults (confirmed live against
 * `POST /v1/realtime/client_secrets`, 2026-08 — not copied from a blog post, verified against
 * the real API response), used as the starting point per 9節. They are explicitly NOT the
 * final values: 9節 requires changing them from real in-car noise evaluation and recording
 * that history per device profile (docs/aec-device-profiles.md), not hardcoding a single
 * "correct" value here.
 */
data class RealtimeVadConfig(
    val threshold: Double = 0.5,
    val prefixPaddingMs: Int = 300,
    val silenceDurationMs: Int = 500,
) {
    /** `session.audio.input.turn_detection` payload for [RealtimeEventCodec.encodeSessionUpdate]. */
    fun toTurnDetectionJson(): JSONObject = JSONObject()
        .put("type", "server_vad")
        .put("threshold", threshold)
        .put("prefix_padding_ms", prefixPaddingMs)
        .put("silence_duration_ms", silenceDurationMs)
        .put("create_response", true)
        .put("interrupt_response", true)

    /**
     * A full `session.update` client event applying this VAD config — nested under
     * `session.audio.input.turn_detection`, matching the real session schema (verified live,
     * 2026-08), not top-level.
     */
    fun toSessionUpdateEvent(): String =
        RealtimeEventCodec.encodeSessionUpdate(
            JSONObject()
                .put("type", "realtime")
                .put(
                    "audio",
                    JSONObject().put(
                        "input",
                        JSONObject().put("turn_detection", toTurnDetectionJson()),
                    ),
                )
        )
}
