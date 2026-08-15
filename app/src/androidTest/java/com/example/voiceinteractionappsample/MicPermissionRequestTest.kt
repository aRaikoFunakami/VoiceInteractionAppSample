package com.example.voiceinteractionappsample

import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.example.voiceinteractionappsample.localagent.LocalAgentRuntime
import com.example.voiceinteractionappsample.realtime.RealtimeServerMode
import com.example.voiceinteractionappsample.realtime.RealtimeServerSettings
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * issue #63: RECORD_AUDIO の実行時権限リクエスト(`MicPermissionGate`)の実機検証。
 *
 * [LocalAgentViaIntegrationTest] とは別クラスにしている理由: Android は「実行中の自分自身の
 * プロセスに対する `pm revoke`」でそのプロセスを強制終了する(revoke は通常、権限に紐づく
 * リソースを無効化するためプロセスを道連れにする)。androidTest はテストコードが対象アプリと
 * 同一プロセスで動くため、テストメソッドの中で自分自身の RECORD_AUDIO を revoke すると
 * その場でテストプロセスごと死ぬ(実際にクラッシュさせて確認済み: `ActivityManager: Crash of
 * app com.example.voiceinteractionappsample running instrumentation`)。
 *
 * そのため、このテストは「RECORD_AUDIO が付与されていない状態でプロセスが起動している」
 * ことを前提条件として要求し、満たされていなければ `assumeTrue` でスキップする
 * (`GrantPermissionRule` は意図的に使わない — 使うと毎回自動で事前付与されてしまう)。
 * `connectedAndroidTest` の一括実行では(他のテストが先に権限を付与済みのため)スキップ
 * されるのが通常であり、それ自体はテストの欠陥ではない。単体で確実に検証するには:
 *
 * ```bash
 * adb shell pm revoke --user <userId> com.example.voiceinteractionappsample android.permission.RECORD_AUDIO
 * adb shell am force-stop com.example.voiceinteractionappsample
 * ./gradlew :app:connectedDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.class=com.example.voiceinteractionappsample.MicPermissionRequestTest
 * ```
 */
@RunWith(AndroidJUnit4::class)
class MicPermissionRequestTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val uiAutomation = InstrumentationRegistry.getInstrumentation().uiAutomation
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    private fun shell(cmd: String): String =
        uiAutomation.executeShellCommand(cmd).use { pfd ->
            java.io.FileInputStream(pfd.fileDescriptor).use { it.readBytes().toString(Charsets.UTF_8) }
        }

    @Before
    fun setUp() {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        assumeTrue(
            "RECORD_AUDIO is already granted — run `pm revoke` + force-stop first (see class kdoc)",
            !granted,
        )
        assumeTrue("models not pushed", LocalAgentRuntime.modelsAvailable())
        assumeTrue(".so not loaded (non-arm64?)", LocalAgentRuntime.engineLoaded())

        RealtimeServerSettings(context).mode = RealtimeServerMode.LOCAL_AGENT

        val userId = shell("am get-current-user").trim()
        val component = "$PACKAGE/$PACKAGE.via.VoiceInteractionServiceImpl"
        shell("settings put --user $userId secure voice_interaction_service $component")
        shell("settings put --user $userId secure assistant $component")
        Thread.sleep(1000)
    }

    @Test
    fun ptt_withRecordAudioNotGranted_showsSystemDialogAndStartsAfterGrant() {
        shell("cmd voiceinteraction show")

        // issue #63 で最初に発見したバグ: VoiceInteractionSession のウィンドウが既定で画面
        // 全体を占有し、NOT_TOUCH_MODAL が付いていても「ウィンドウの外側」が存在しないため
        // ダイアログへのタップを吸収してしまっていた。CarVoiceInteractionSession 側のウィンドウ
        // サイズ修正(WRAP_CONTENT 化)で解決済み — このテストはその回帰を検知する。
        val dialogShown = device.wait(Until.hasObject(By.textContains("record audio")), 15_000)
        assertTrueWithScreen("permission dialog not shown", dialogShown)

        val allowButton = device.findObject(By.textStartsWith("While using"))
        assertTrueWithScreen("Allow button not tappable (window may be blocking touch)", allowButton != null)
        allowButton!!.click()

        // 権限付与後、MicPermissionGate.request() が再開して通常どおりセッションが始まること。
        val greeted = device.wait(Until.hasObject(By.textContains("こんにちは")), 60_000)
        assertTrueWithScreen("session did not start after granting permission", greeted)

        assertTrueWithScreen(
            "RECORD_AUDIO not actually granted",
            shell("dumpsys package $PACKAGE").contains("android.permission.RECORD_AUDIO: granted=true"),
        )

        shell("cmd voiceinteraction hide")
    }

    private fun assertTrueWithScreen(message: String, condition: Boolean) {
        if (!condition) {
            Log.e(TAG, "FAILED: $message")
            Log.e(TAG, "screen dump: ${device.findObjects(By.pkg(PACKAGE)).joinToString { it.text ?: "" }}")
        }
        org.junit.Assert.assertTrue(message, condition)
    }

    private companion object {
        const val TAG = "MicPermissionRequestTest"
        const val PACKAGE = "com.example.voiceinteractionappsample"
    }
}
