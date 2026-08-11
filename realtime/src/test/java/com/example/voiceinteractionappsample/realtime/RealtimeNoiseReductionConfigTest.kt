package com.example.voiceinteractionappsample.realtime

import org.junit.Assert.assertEquals
import org.junit.Test

class RealtimeNoiseReductionConfigTest {

    @Test
    fun defaultsToNearField() {
        assertEquals("near_field", RealtimeNoiseReductionConfig().type)
    }

    @Test
    fun toJsonEmitsType() {
        val json = RealtimeNoiseReductionConfig(type = "far_field").toJson()

        assertEquals("far_field", json.getString("type"))
    }
}
