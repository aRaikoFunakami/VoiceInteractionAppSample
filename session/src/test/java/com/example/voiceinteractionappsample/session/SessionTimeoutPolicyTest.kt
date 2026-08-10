package com.example.voiceinteractionappsample.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SessionTimeoutPolicyTest {

    @Test
    fun defaultsMatchTheConfiguredValues() {
        val policy = SessionTimeoutPolicy()

        assertEquals(10_000L, policy.idleTimeoutMs)
        assertEquals(2 * 60_000L, policy.maxSessionDurationMs)
    }

    @Test
    fun rejectsZeroOrNegativeIdleTimeout() {
        assertThrows(IllegalArgumentException::class.java) {
            SessionTimeoutPolicy(idleTimeoutMs = 0)
        }
    }

    @Test
    fun rejectsZeroOrNegativeMaxDuration() {
        assertThrows(IllegalArgumentException::class.java) {
            SessionTimeoutPolicy(maxSessionDurationMs = -1)
        }
    }
}
