package com.example.voiceinteractionappsample.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenYouTubeSearchToolSchemaTest {

    @Test
    fun schemaMatchesPlannedShape() {
        val json = OpenYouTubeSearchToolSchema.toJson()

        assertEquals("function", json.getString("type"))
        assertEquals("open_youtube_search", json.getString("name"))
        val parameters = json.getJSONObject("parameters")
        assertEquals("object", parameters.getString("type"))
        assertTrue(parameters.getJSONArray("required").toString().contains("query"))
        assertFalse(parameters.getBoolean("additionalProperties"))
    }
}
