package com.example.voiceinteractionappsample.diagnostics

import android.app.Activity
import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView
import com.example.voiceinteractionappsample.audio.AecMode
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 21節: 起動時診断画面。診断buildでの確認用 — 本番のエンドユーザー向けUIではない。
 * ConversationControllerが無い状態（未接続）でも表示できるよう、controller引数はnull許容。
 */
class DiagnosticsActivity : Activity() {

    private val scope = MainScope()
    private lateinit var textView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        textView = TextView(this).apply {
            textSize = 14f
            setPadding(32, 32, 32, 32)
            text = "Loading diagnostics…"
        }
        setContentView(ScrollView(this).apply { addView(textView) })

        scope.launch {
            val snapshot = DiagnosticsCollector.collect(applicationContext, AecMode.AUTO)
            textView.text = format(snapshot)
        }
    }

    private fun format(s: DiagnosticsSnapshot): String = buildString {
        appendLine("build fingerprint: ${s.buildFingerprint}")
        appendLine("API level: ${s.apiLevel} (Android ${s.androidRelease})")
        appendLine()
        appendLine("active VoiceInteractionService: ${s.activeVoiceInteractionService ?: "none"}")
        appendLine("ROLE_ASSISTANT held: ${s.isRoleAssistantHeld}")
        appendLine()
        appendLine("input audio devices: ${s.inputAudioDevices.ifEmpty { listOf("none") }.joinToString()}")
        appendLine("output audio devices: ${s.outputAudioDevices.ifEmpty { listOf("none") }.joinToString()}")
        appendLine("input sample rate: ${s.inputSampleRate}")
        appendLine("output sample rate: ${s.outputSampleRate}")
        appendLine()
        appendLine("hardware AEC supported: ${s.hardwareAecSupported}")
        appendLine("hardware NS supported: ${s.hardwareNsSupported}")
        appendLine("selected AEC mode: ${s.selectedAecMode}")
        appendLine()
        appendLine("libwebrtc: ${s.libwebrtcLibrary}")
        appendLine("supported ABIs: ${s.supportedAbis.joinToString()}")
        appendLine()
        appendLine("backend reachable: ${s.backendReachable}")
        appendLine("connection state: ${s.connectionState}")
        appendLine("ICE state: ${s.iceState ?: "n/a"}")
        appendLine("selected candidate pair: ${s.selectedCandidatePair ?: "n/a"}")
        appendLine()
        appendLine("browser ACTION_VIEW handler available: ${s.browserActionViewHandlerAvailable}")
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
