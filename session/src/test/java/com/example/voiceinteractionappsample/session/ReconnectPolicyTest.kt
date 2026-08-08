package com.example.voiceinteractionappsample.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReconnectPolicyTest {

    @Test
    fun backoffDoublesEachAttemptUpToMax() {
        val policy = ReconnectPolicy(maxAttempts = 5, initialBackoffMs = 1_000, backoffMultiplier = 2.0, maxBackoffMs = 10_000)

        assertEquals(1_000, policy.backoffForAttempt(1))
        assertEquals(2_000, policy.backoffForAttempt(2))
        assertEquals(4_000, policy.backoffForAttempt(3))
        assertEquals(8_000, policy.backoffForAttempt(4))
        assertEquals(10_000, policy.backoffForAttempt(5)) // would be 16000, capped at maxBackoffMs
    }

    @Test
    fun exhaustedOnceAttemptsExceedMax() {
        val policy = ReconnectPolicy(maxAttempts = 3)

        assertFalse(policy.isExhausted(1))
        assertFalse(policy.isExhausted(3))
        assertTrue(policy.isExhausted(4))
    }

    @Test
    fun maxAttemptsZeroMeansNoRetryAtAll() {
        val policy = ReconnectPolicy(maxAttempts = 0)

        assertTrue(policy.isExhausted(1))
    }
}
