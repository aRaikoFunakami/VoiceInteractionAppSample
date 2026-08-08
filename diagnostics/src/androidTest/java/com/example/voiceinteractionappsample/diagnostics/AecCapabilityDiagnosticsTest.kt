package com.example.voiceinteractionappsample.diagnostics

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs on-device: exercises the real JNI binding to the pinned WebRTC AAR
 * (third_party/libwebrtc/README.md), not just a JVM-level API check. A native library
 * missing for this device's ABI, or a JNI method signature mismatch, fails here rather than
 * silently at first real use in Phase 5.
 */
@RunWith(AndroidJUnit4::class)
class AecCapabilityDiagnosticsTest {

    @Test
    fun readDoesNotCrashAndReturnsCapability() {
        InstrumentationRegistry.getInstrumentation()

        val result = AecCapabilityDiagnostics.read()

        assertNotNull(result)
    }
}
