package com.example.voiceinteractionappsample.session

import android.Manifest
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.example.voiceinteractionappsample.realtime.MockRealtimeCredentialProvider
import java.time.Instant
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Billing-risk safety net (26節, added after user feedback): a connected Realtime session
 * costs money for as long as it's open. [SessionTimeoutPolicy.maxSessionDurationMs] is the
 * unconditional backstop — fires regardless of activity, which matters because ambient-noise-
 * triggered VAD chatter would keep resetting an idle timer forever without ever going quiet.
 *
 * Uses a tiny maxSessionDurationMs (3s) so the test doesn't wait minutes; the watchdog check
 * interval is fixed at 5s in ConversationController, so this also proves the check interval
 * itself doesn't block the timeout from firing promptly after it's due.
 *
 * adb shell am instrument -w -e openaiEphemeralSecret <secret> \
 *   -e class com.example.voiceinteractionappsample.session.SessionTimeoutWatchdogLiveTest \
 *   com.example.voiceinteractionappsample.session.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class SessionTimeoutWatchdogLiveTest {

    @get:Rule
    val permissionRule: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)

    @Test
    fun maxSessionDurationForcesDisconnectRegardlessOfActivity() = runBlocking {
        val secret = InstrumentationRegistry.getArguments().getString("openaiEphemeralSecret")
        assumeNotNull(secret)

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val controller = ConversationController(
            context,
            MockRealtimeCredentialProvider(secret!!, Instant.now().plusSeconds(60)),
            sessionTimeoutPolicy = SessionTimeoutPolicy(idleTimeoutMs = 60_000, maxSessionDurationMs = 3_000),
        )

        controller.start()
        delay(2_000) // let SDP negotiation actually land on CONNECTED
        assertEquals(ConnectionState.CONNECTED, controller.state.value.connection)

        // maxSessionDurationMs=3s already elapsed by now; give the 5s-interval watchdog one
        // full cycle plus margin to actually act on it.
        delay(8_000)

        assertEquals(
            "expected the watchdog to have force-cancelled the session by now",
            ConversationSessionState(),
            controller.state.value,
        )
    }
}
