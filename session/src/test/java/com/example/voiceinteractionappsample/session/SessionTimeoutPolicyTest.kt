package com.example.voiceinteractionappsample.session

import org.junit.Assert.assertThrows
import org.junit.Test

class SessionTimeoutPolicyTest {

    @Test
    fun defaultsAreValid() {
        SessionTimeoutPolicy() // must not throw
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
