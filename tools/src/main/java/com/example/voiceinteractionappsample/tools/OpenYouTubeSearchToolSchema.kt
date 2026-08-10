package com.example.voiceinteractionappsample.tools

import org.json.JSONArray
import org.json.JSONObject

/**
 * 11節: `open_youtube_search` tool schema, registered in `session.update`'s `tools` array.
 * Used when the user explicitly asks to watch/find a video — not for informational questions
 * ("YouTubeとは何か", "この曲について教えて"), which the description spells out so the model
 * doesn't over-trigger it.
 */
object OpenYouTubeSearchToolSchema {
    const val NAME = "open_youtube_search"

    fun toJson(): JSONObject = JSONObject()
        .put("type", "function")
        .put("name", NAME)
        .put("description", "Open a YouTube search when the user explicitly asks to watch or find a video.")
        .put(
            "parameters",
            JSONObject()
                .put("type", "object")
                .put("properties", JSONObject().put("query", JSONObject().put("type", "string")))
                .put("required", JSONArray().put("query"))
                .put("additionalProperties", false),
        )
}
