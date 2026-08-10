package com.example.voiceinteractionappsample.session

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.voiceinteractionappsample.realtime.MockRealtimeCredentialProvider
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #30 (10-1): microphone permission拒否。
 *
 * 意図的に [androidx.test.rule.GrantPermissionRule] を使わない — RECORD_AUDIO は
 * `adb shell pm revoke` で外部から明示的に剥奪した状態でこのテストだけ実行する運用。
 *
 * adb shell pm revoke com.example.voiceinteractionappsample.session.test android.permission.RECORD_AUDIO
 * adb shell am instrument -w -e class ...MicPermissionDeniedLiveTest ...
 * adb shell pm grant com.example.voiceinteractionappsample.session.test android.permission.RECORD_AUDIO
 */
@RunWith(AndroidJUnit4::class)
class MicPermissionDeniedLiveTest {

    @Test
    fun startingWithoutMicPermissionDoesNotCrashTheProcess() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val controller = ConversationController(
            context,
            MockRealtimeCredentialProvider("unused-network-not-reached-necessarily", Instant.now()),
        )

        // Whether start() throws or JavaAudioDeviceModule just reports an error callback
        // internally, the one thing that must never happen is the process crashing — swallow
        // any exception here and just prove cancel() still cleans up safely afterward.
        try {
            controller.start()
        } catch (e: Exception) {
            // expected possibility, not a test failure by itself
        }

        controller.cancel()
    }
}
