package com.example.voiceinteractionappsample.realtime

import org.json.JSONObject

/** Token breakdown from a `response.done` event's `response.usage` object. */
data class RealtimeUsage(
    val totalTokens: Int,
    val textInputTokens: Int,
    val audioInputTokens: Int,
    val cachedTextInputTokens: Int,
    val cachedAudioInputTokens: Int,
    val textOutputTokens: Int,
    val audioOutputTokens: Int,
)

/**
 * Estimates USD cost from Realtime API `usage` payloads, priced for the `gpt-realtime-2.1`
 * model the backend broker requests (backend/local_broker.py: `MODEL`). Prices are per-1M-token
 * USD from https://developers.openai.com/api/docs/pricing (checked 2026-08-11).
 *
 * ponytail: single hardcoded price table for the one model this app uses, not a lookup-by-model
 * config — add a model->price map only if the broker ever requests more than one model.
 */
object RealtimeUsageCost {
    private const val PRICE_TEXT_INPUT = 4.00 / 1_000_000
    private const val PRICE_AUDIO_INPUT = 32.00 / 1_000_000
    private const val PRICE_CACHED_TEXT_INPUT = 0.40 / 1_000_000
    private const val PRICE_CACHED_AUDIO_INPUT = 0.40 / 1_000_000
    private const val PRICE_TEXT_OUTPUT = 24.00 / 1_000_000
    private const val PRICE_AUDIO_OUTPUT = 64.00 / 1_000_000

    /** Parses a `response.usage` JSON object; returns null if it's absent or malformed. */
    fun parseUsage(usage: JSONObject?): RealtimeUsage? {
        if (usage == null) return null
        val inputDetails = usage.optJSONObject("input_token_details")
        val cachedDetails = inputDetails?.optJSONObject("cached_tokens_details")
        val outputDetails = usage.optJSONObject("output_token_details")
        return RealtimeUsage(
            totalTokens = usage.optInt("total_tokens"),
            textInputTokens = inputDetails?.optInt("text_tokens") ?: 0,
            audioInputTokens = inputDetails?.optInt("audio_tokens") ?: 0,
            cachedTextInputTokens = cachedDetails?.optInt("text_tokens") ?: 0,
            cachedAudioInputTokens = cachedDetails?.optInt("audio_tokens") ?: 0,
            textOutputTokens = outputDetails?.optInt("text_tokens") ?: 0,
            audioOutputTokens = outputDetails?.optInt("audio_tokens") ?: 0,
        )
    }

    /** `text/audio_tokens` already include the cached subset — split before pricing them differently. */
    fun estimateCostUsd(usage: RealtimeUsage): Double {
        val uncachedText = (usage.textInputTokens - usage.cachedTextInputTokens).coerceAtLeast(0)
        val uncachedAudio = (usage.audioInputTokens - usage.cachedAudioInputTokens).coerceAtLeast(0)
        return uncachedText * PRICE_TEXT_INPUT +
            usage.cachedTextInputTokens * PRICE_CACHED_TEXT_INPUT +
            uncachedAudio * PRICE_AUDIO_INPUT +
            usage.cachedAudioInputTokens * PRICE_CACHED_AUDIO_INPUT +
            usage.textOutputTokens * PRICE_TEXT_OUTPUT +
            usage.audioOutputTokens * PRICE_AUDIO_OUTPUT
    }
}
