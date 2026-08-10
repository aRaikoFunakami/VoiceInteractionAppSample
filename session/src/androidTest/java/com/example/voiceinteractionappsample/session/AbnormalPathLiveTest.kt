package com.example.voiceinteractionappsample.session

import android.Manifest
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.example.voiceinteractionappsample.realtime.MockRealtimeCredentialProvider
import com.example.voiceinteractionappsample.realtime.RealtimeConnectionException
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #30 (10-1): AAOS異常系 — credential失敗 / microphone permission拒否。
 *
 * network切断・reconnectionはPhase 7 (#24) で、Intent handlerなしはPhase 9 (#28) で既に
 * カバー済み。WebRTC失敗（createPeerConnectionがnullを返す等）はコード上
 * RealtimeConnectionException を投げる構造になっているが、通常条件で意図的に再現するのが
 * 難しく、ここでは追わない。走行中UX restrictionは DeviceTool.checkUxRestriction() に
 * フックだけ用意されており、実際の CarUxRestrictionsManager 連携は未実装（スコープ外として
 * 明示的に残す）。
 */
@RunWith(AndroidJUnit4::class)
class AbnormalPathLiveTest {

    @get:Rule
    val permissionRule: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)

    @Test
    fun invalidCredentialFailsCleanlyWithoutLeakingResources() = runBlocking {
        // 実ネットワーク到達性は必要だが、OpenAIから発行された本物のephemeral secretは不要
        // — 無効な値でOpenAI側から401/400が返ることを確認する（credential失敗の異常系）。
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val controller = ConversationController(
            context,
            MockRealtimeCredentialProvider("invalid-not-a-real-secret", Instant.now().plusSeconds(60)),
        )

        var threw = false
        try {
            controller.start()
        } catch (e: RealtimeConnectionException) {
            threw = true
        }

        assertTrue("expected RealtimeWebRtcClient to reject an invalid credential, not silently succeed", threw)

        // 異常終了後もcancel()が安全に呼べること（7-2節と同じ保証をcredential失敗経路でも）。
        controller.cancel()
        assertEquals(ConversationSessionState(), controller.state.value)
    }
}
