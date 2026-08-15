package com.example.voiceinteractionappsample

import android.Manifest
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.example.voiceinteractionappsample.localagent.LocalAgentRuntime
import com.example.voiceinteractionappsample.realtime.RealtimeServerMode
import com.example.voiceinteractionappsample.realtime.RealtimeServerSettings
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * issue #59: LOCAL_AGENT を実際の VIA(VoiceInteractionSession)経路から起動する統合テスト。
 *
 * 経緯: #48〜#50 で追加した `LocalAgentE2eTest`/`LocalAgentControllerTest` は
 * `LocalAgentController` を直接 `new` して `start()` を呼ぶだけで、
 * `CarVoiceInteractionSession`/`VoiceInteractionService` を一切経由していなかった。
 * AAOS 実機(automotive AVD)上では動いていたが、それは「AAOS の VIA 統合を検証した」
 * ことにはならない — 汎用の Android インストゥルメンテーションテストがたまたま AAOS
 * イメージ上で動いていただけだった。この指摘を受けて追加した。
 *
 * 本テストは以下すべてを実際の経路で行う:
 * - モードの選択: `RealtimeServerSettings(context).mode = LOCAL_AGENT`
 *   (`ServerSettingsActivity` 自身もこの public API を呼ぶだけなので UI 経由と等価。
 *   root 権限で SharedPreferences の XML を直接書き換えるようなことはしない)
 * - アシスタント登録: `settings put secure voice_interaction_service`
 *   (docs/how-to-run.md 手順4に明記された正規の adb 手順。root 不要、shell 権限で足りる)
 * - PTT: `cmd voiceinteraction show`(docs/how-to-run.md 手順6に明記された正規の代替手段。
 *   これが実際に `CarVoiceInteractionSession.onShow()` を呼ぶ)
 * - 検証: UiAutomator で実画面から Voice Plate の TextView を読み取る。
 *   `LocalAgentController`/`CarVoiceInteractionSession` の内部状態を直接覗くのではなく、
 *   実際に画面に出ているものだけを見る。
 */
@RunWith(AndroidJUnit4::class)
class LocalAgentViaIntegrationTest {

    @get:Rule
    val micPermission: androidx.test.rule.GrantPermissionRule =
        androidx.test.rule.GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val uiAutomation = InstrumentationRegistry.getInstrumentation().uiAutomation
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    private var userId: String = "0"
    private var originalVoiceInteractionService: String = ""
    private var originalAssistant: String = ""

    private fun shell(cmd: String): String =
        uiAutomation.executeShellCommand(cmd).use { pfd ->
            java.io.FileInputStream(pfd.fileDescriptor).use { it.readBytes().toString(Charsets.UTF_8) }
        }

    @Before
    fun setUp() {
        assumeTrue("models not pushed", LocalAgentRuntime.modelsAvailable())
        assumeTrue(".so not loaded (non-arm64?)", LocalAgentRuntime.engineLoaded())

        userId = shell("am get-current-user").trim()
        originalVoiceInteractionService = shell("settings get --user $userId secure voice_interaction_service").trim()
        originalAssistant = shell("settings get --user $userId secure assistant").trim()

        RealtimeServerSettings(context).mode = RealtimeServerMode.LOCAL_AGENT

        val component = "$PACKAGE/$PACKAGE.via.VoiceInteractionServiceImpl"
        shell("settings put --user $userId secure voice_interaction_service $component")
        shell("settings put --user $userId secure assistant $component")

        // レジストリの反映待ち(実機で確認: 直後に show するとまだ古いサービスが応答することがある)
        Thread.sleep(1000)
    }

    @After
    fun tearDown() {
        shell("cmd voiceinteraction hide")
        if (originalVoiceInteractionService.isNotBlank() && originalVoiceInteractionService != "null") {
            shell("settings put --user $userId secure voice_interaction_service $originalVoiceInteractionService")
        }
        if (originalAssistant.isNotBlank() && originalAssistant != "null") {
            shell("settings put --user $userId secure assistant $originalAssistant")
        }
    }

    @Test
    fun ptt_throughRealVoiceInteractionSession_showsGreetingThenTogglesAndRetries() {
        // 1. 実 PTT(cmd voiceinteraction show)で CarVoiceInteractionSession.onShow() を起動する。
        //    LocalAgentController.start() を直接呼んでいない — VIA フレームワークが呼ぶ。
        shell("cmd voiceinteraction show")

        // 2. 実画面から Voice Plate を探す。モデルロード(初回 ~10 秒)を待つため長めに取る。
        val greeted = device.wait(Until.hasObject(By.textContains("こんにちは")), 60_000)
        val plate = device.findObject(By.textContains("接続:"))
        Log.i(TAG, "after PTT#1: greeted=$greeted plateText=${plate?.text}")
        assertTrueWithScreen("greeting not shown on real screen within 60s", greeted)
        assertTrueWithScreen(
            "Voice Plate does not show CONNECTED: ${plate?.text}",
            plate?.text?.contains("CONNECTED") == true,
        )

        // 3. 実 PTT をもう一度(toggle-to-stop)。Voice Plate が実際に消えることを確認する
        //    (#47 で直したガード条件 — FAILED latch とは別経路だが、同じ toggle 判定を通る)。
        shell("cmd voiceinteraction show")
        val hidden = device.wait(Until.gone(By.textContains("接続:")), 5_000)
        assertTrueWithScreen("Voice Plate did not disappear after toggle-to-stop", hidden)

        // 4. 再度 PTT。#47 で直した「失敗後/終了後の PTT が新規セッションとして再試行できる」を
        //    LocalAgentController の内部 API 経由ではなく、実際に VIA からもう一度起動して検証する。
        shell("cmd voiceinteraction show")
        val greetedAgain = device.wait(Until.hasObject(By.textContains("こんにちは")), 60_000)
        assertTrueWithScreen("second PTT did not start a fresh session on real screen", greetedAgain)
    }

    private fun assertTrueWithScreen(message: String, condition: Boolean) {
        if (!condition) {
            Log.e(TAG, "FAILED: $message")
            Log.e(TAG, "screen dump: ${device.findObjects(By.pkg(PACKAGE)).joinToString { it.text ?: "" }}")
        }
        org.junit.Assert.assertTrue(message, condition)
    }

    private companion object {
        const val TAG = "LocalAgentViaIntegrationTest"
        const val PACKAGE = "com.example.voiceinteractionappsample"
    }
}
