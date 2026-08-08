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
 * Issue #22/#23 (7-1, 7-2): lifecycle ordering + cancel idempotency/safety from any state.
 *
 * adb shell am instrument -w -e openaiEphemeralSecret <secret> \
 *   -e class com.example.voiceinteractionappsample.session.ConversationControllerLifecycleLiveTest \
 *   com.example.voiceinteractionappsample.session.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class ConversationControllerLifecycleLiveTest {

    @get:Rule
    val permissionRule: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)

    @Test
    fun cancelBeforeEverStartingIsANoOp() = runBlocking {
        // 7-2節: 「全conversation状態から」には最初のIDLE状態も含む — start()すら呼ばれて
        // いない状態でcancel()してもクラッシュしないことは、実クレデンシャル無しで確認できる。
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val controller = ConversationController(
            context,
            MockRealtimeCredentialProvider("unused", Instant.now()),
        )

        controller.cancel()

        assertEquals(ConversationSessionState(), controller.state.value)
    }

    @Test
    fun cancelFromConnectedStateReleasesEverythingAndIsIdempotent() = runBlocking {
        val secret = InstrumentationRegistry.getArguments().getString("openaiEphemeralSecret")
        assumeNotNull(secret)

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val controller = ConversationController(
            context,
            MockRealtimeCredentialProvider(secret!!, Instant.now().plusSeconds(60)),
        )

        controller.start()
        delay(2_000) // let SDP negotiation actually land on CONNECTED

        assertEquals(ConnectionState.CONNECTED, controller.state.value.connection)
        assertEquals(AudioInputState.CAPTURING, controller.state.value.audioInput)

        controller.cancel(DisconnectReason.USER_CANCEL)

        assertEquals(ConversationSessionState(), controller.state.value)

        // 7-2節: idempotent — calling again from the now-disconnected state must not throw.
        controller.cancel(DisconnectReason.USER_CANCEL)
        assertEquals(ConversationSessionState(), controller.state.value)
    }
}
