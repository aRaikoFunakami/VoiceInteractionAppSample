package com.example.voiceinteractionappsample.realtime

import org.json.JSONObject

/**
 * conversation/response/function-call/error event decode-encode skeleton (4節, 3-4節). Only
 * extracts `type` for now — richer per-type models are added as each feature needs them
 * (barge-in event handling: Phase 6, function call handling: Phase 8), not speculatively here.
 */
data class RealtimeEvent(val type: String, val raw: JSONObject)

object RealtimeEventCodec {
    fun decode(json: String): RealtimeEvent {
        val obj = JSONObject(json)
        return RealtimeEvent(type = obj.optString("type", "unknown"), raw = obj)
    }

    fun encodeSessionUpdate(session: JSONObject): String =
        JSONObject().apply {
            put("type", "session.update")
            put("session", session)
        }.toString()
}
