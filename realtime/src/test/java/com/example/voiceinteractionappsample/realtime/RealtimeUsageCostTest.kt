package com.example.voiceinteractionappsample.realtime

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RealtimeUsageCostTest {

    @Test
    fun parseUsageExtractsAllBreakdowns() {
        val usage = JSONObject(
            """
            {
              "total_tokens": 800,
              "input_token_details": {
                "text_tokens": 200,
                "audio_tokens": 300,
                "cached_tokens_details": {"text_tokens": 100, "audio_tokens": 100}
              },
              "output_token_details": {"text_tokens": 100, "audio_tokens": 200}
            }
            """.trimIndent(),
        )

        val parsed = RealtimeUsageCost.parseUsage(usage)

        assertEquals(
            RealtimeUsage(
                totalTokens = 800,
                textInputTokens = 200,
                audioInputTokens = 300,
                cachedTextInputTokens = 100,
                cachedAudioInputTokens = 100,
                textOutputTokens = 100,
                audioOutputTokens = 200,
            ),
            parsed,
        )
    }

    @Test
    fun parseUsageReturnsNullWhenMissing() {
        assertNull(RealtimeUsageCost.parseUsage(null))
    }

    @Test
    fun estimateCostUsdPricesCachedAndUncachedTokensSeparately() {
        // 100 uncached text in, 200 uncached audio in, 100 cached text, 100 cached audio,
        // 100 text out, 200 audio out.
        val usage = RealtimeUsage(
            totalTokens = 800,
            textInputTokens = 200,
            audioInputTokens = 300,
            cachedTextInputTokens = 100,
            cachedAudioInputTokens = 100,
            textOutputTokens = 100,
            audioOutputTokens = 200,
        )

        val cost = RealtimeUsageCost.estimateCostUsd(usage)

        val expected = 100 * (4.00 / 1_000_000) +
            100 * (0.40 / 1_000_000) +
            200 * (32.00 / 1_000_000) +
            100 * (0.40 / 1_000_000) +
            100 * (24.00 / 1_000_000) +
            200 * (64.00 / 1_000_000)
        assertEquals(expected, cost, 1e-12)
    }
}
