package com.example.voiceinteractionappsample.via

import android.content.Context
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.view.View
import com.example.voiceinteractionappsample.realtime.HttpRealtimeCredentialProvider
import com.example.voiceinteractionappsample.session.AudioOutputState
import com.example.voiceinteractionappsample.session.ConnectionState
import com.example.voiceinteractionappsample.session.ConversationController
import com.example.voiceinteractionappsample.session.ConversationSessionState
import com.example.voiceinteractionappsample.session.ConversationState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * Owns Voice Plate + the conversation session lifecycle (1節, 17節) — and actually starts/stops
 * a real Realtime conversation via [ConversationController] (7-1節: this was the missing half
 * of "接続" — ConversationController existed and was tested standalone since Phase 7, but was
 * never wired to the session PTT/TTT actually drives, so pressing PTT only ever showed the
 * Voice Plate without connecting to anything).
 *
 * Needs `backend/local_broker.py` running on the host — Session Broker itself is out of scope
 * for this repo (docs/broker-contract.md). See docs/how-to-run.md.
 */
class CarVoiceInteractionSession(context: Context) : VoiceInteractionSession(context) {

    // Dispatchers.Main is required here — controller.state emits from ConversationController's
    // own background-dispatched coroutines, and VoicePlateView.setState() touches a real View,
    // which crashes off the main thread. Found live: the first end-to-end run threw exactly
    // this (ViewRootImpl rejecting the setState() call chain from a background thread).
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var voicePlateView: VoicePlateView? = null
    private val controller = ConversationController(
        context = context,
        credentialProvider = HttpRealtimeCredentialProvider(LOCAL_BROKER_URL),
    )

    override fun onCreateContentView(): View =
        VoicePlateView(context).also { voicePlateView = it }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        controller.state.onEach { updateVoicePlate(it) }.launchIn(scope)
        scope.launch {
            try {
                controller.start()
            } catch (e: Exception) {
                voicePlateView?.setState(VoicePlateState.ERROR)
            }
        }
    }

    override fun onHide() {
        // 17節: onHide()と完全終了を厳密に区別するにはAAOS上のhide理由取得が必要で未確定
        // （26節）。このサンプルでは簡略化して「hideされたら会話も終える」とする —
        // ponytail: この単純化には天井がある。hide理由（バックグラウンド遷移 vs ユーザーに
        // よる明示的終了）を区別する必要が出たら見直す。
        scope.launch { controller.cancel() }
        super.onHide()
    }

    private fun updateVoicePlate(state: ConversationSessionState) {
        val plateState = when {
            state.connection == ConnectionState.FAILED -> VoicePlateState.ERROR
            state.connection == ConnectionState.CONNECTING -> VoicePlateState.WORKING
            state.audioOutput == AudioOutputState.PLAYING -> VoicePlateState.SPEAKING
            state.conversation == ConversationState.MODEL_PROCESSING -> VoicePlateState.THINKING
            else -> VoicePlateState.LISTENING
        }
        voicePlateView?.setState(plateState)
    }

    private companion object {
        // AVD限定 (10.0.2.2 = host loopback)。実機/実Brokerでは差し替える。
        const val LOCAL_BROKER_URL = "http://10.0.2.2:8787/api/realtime/session"
    }
}
