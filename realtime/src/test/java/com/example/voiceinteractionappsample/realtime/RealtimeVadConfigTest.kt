package com.example.voiceinteractionappsample.realtime

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RealtimeVadConfigTest {

    @Test
    fun defaultsMatchOpenAisOwnObservedSessionDefaults() {
        val config = RealtimeVadConfig()

        assertEquals(0.5, config.threshold, 0.0)
        assertEquals(300, config.prefixPaddingMs)
        assertEquals(500, config.silenceDurationMs)
    }

    @Test
    fun turnDetectionJsonAlwaysEnablesInterruption() {
        val json = RealtimeVadConfig(threshold = 0.7, prefixPaddingMs = 200, silenceDurationMs = 400)
            .toTurnDetectionJson()

        assertEquals("server_vad", json.getString("type"))
        assertEquals(0.7, json.getDouble("threshold"), 0.0)
        assertEquals(200, json.getInt("prefix_padding_ms"))
        assertEquals(400, json.getInt("silence_duration_ms"))
        // 9節: barge-inのため create_response/interrupt_response は常にtrue固定。
        assertTrue(json.getBoolean("create_response"))
        assertTrue(json.getBoolean("interrupt_response"))
    }

    @Test
    fun sessionUpdateEventNestsTurnDetectionUnderAudioInput() {
        val event = JSONObject(RealtimeVadConfig().toSessionUpdateEvent())

        assertEquals("session.update", event.getString("type"))
        val turnDetection = event.getJSONObject("session")
            .getJSONObject("audio")
            .getJSONObject("input")
            .getJSONObject("turn_detection")
        assertEquals("server_vad", turnDetection.getString("type"))
    }
}
