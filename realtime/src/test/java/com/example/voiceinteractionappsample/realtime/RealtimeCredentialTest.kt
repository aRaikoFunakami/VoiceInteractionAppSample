package com.example.voiceinteractionappsample.realtime

import java.time.Instant
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RealtimeCredentialTest {

    @Test
    fun toStringDoesNotLeakClientSecret() {
        val credential = RealtimeCredential(
            clientSecret = "super-secret-value",
            expiresAt = Instant.parse("2026-08-08T12:00:00Z"),
            sessionConfigVersion = "v1",
        )

        val text = credential.toString()

        assertFalse(text.contains("super-secret-value"))
        assertTrue(text.contains("v1"))
    }
}
