package com.example.voiceinteractionappsample.diagnostics

import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.example.voiceinteractionappsample.audio.AecMode
import com.example.voiceinteractionappsample.session.ConversationController
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 21節: 起動時診断項目を集める。診断buildのみで使う想定 — 本番UIには出さない。 */
object DiagnosticsCollector {

    suspend fun collect(
        context: Context,
        selectedAecMode: AecMode,
        controller: ConversationController? = null,
    ): DiagnosticsSnapshot {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val aecCapability = AecCapabilityDiagnostics.read()
        val connectionState = controller?.state?.value

        return DiagnosticsSnapshot(
            buildFingerprint = Build.FINGERPRINT,
            apiLevel = Build.VERSION.SDK_INT,
            androidRelease = Build.VERSION.RELEASE,
            activeVoiceInteractionService = readActiveVoiceInteractionService(context),
            isRoleAssistantHeld = isRoleAssistantHeld(context),
            inputAudioDevices = describeDevices(audioManager, AudioManager.GET_DEVICES_INPUTS),
            outputAudioDevices = describeDevices(audioManager, AudioManager.GET_DEVICES_OUTPUTS),
            inputSampleRate = audioManager?.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE).orDiagnosticUnknown(),
            outputSampleRate = audioManager?.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE).orDiagnosticUnknown(),
            hardwareAecSupported = aecCapability.hardwareAecSupported,
            hardwareNsSupported = aecCapability.hardwareNsSupported,
            selectedAecMode = selectedAecMode.name,
            // third_party/libwebrtc/VERSION の固定値と一致させる — ズレたらそちらも直す。
            libwebrtcLibrary = "io.getstream:stream-webrtc-android:1.3.10",
            supportedAbis = Build.SUPPORTED_ABIS.toList(),
            backendReachable = withContext(Dispatchers.IO) { checkBackendReachable() },
            connectionState = connectionState?.connection?.name ?: "NOT_CONNECTED",
            iceState = null, // PeerConnection.iceConnectionState() は :realtime内部にありここへ未配線 — Phase 7以降で必要になれば追加する
            selectedCandidatePair = null, // getStats()連携は20節のWebRTC統計取得と合わせて別途配線する
            browserActionViewHandlerAvailable = hasYouTubeSearchHandler(context),
        )
    }

    private fun readActiveVoiceInteractionService(context: Context): String? =
        Settings.Secure.getString(context.contentResolver, "voice_interaction_service")
            ?.takeIf { it.isNotBlank() }

    private fun isRoleAssistantHeld(context: Context): Boolean {
        val roleManager = context.getSystemService(Context.ROLE_SERVICE) as? android.app.role.RoleManager
            ?: return false
        return try {
            roleManager.isRoleHeld(android.app.role.RoleManager.ROLE_ASSISTANT)
        } catch (e: Exception) {
            false
        }
    }

    private fun describeDevices(audioManager: AudioManager?, direction: Int): List<String> =
        audioManager?.getDevices(direction)?.map { describeDevice(it) }.orEmpty()

    private fun describeDevice(device: AudioDeviceInfo): String =
        "${device.productName}(type=${device.type})"

    private fun checkBackendReachable(): Boolean = try {
        val connection = URL("https://api.openai.com/").openConnection() as HttpURLConnection
        connection.connectTimeout = 5_000
        connection.readTimeout = 5_000
        connection.requestMethod = "HEAD"
        val status = connection.responseCode
        connection.disconnect()
        status in 200..499 // 到達できたかどうかだけを見る。認証エラー(4xx)も「到達できた」扱い。
    } catch (e: Exception) {
        false
    }

    private fun hasYouTubeSearchHandler(context: Context): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=test"))
        return intent.resolveActivity(context.packageManager) != null
    }

    private fun String?.orDiagnosticUnknown(): String = this ?: "unknown"
}
