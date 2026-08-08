package com.example.voiceinteractionappsample.via

import android.content.Context
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.view.View

/**
 * Owns Voice Plate + the conversation session lifecycle (1節, 17節).
 *
 * Phase 1 only wires PTT/TTT -> Voice Plate display. Realtime connection lifecycle
 * (ConversationController) is NOT wired in here yet — that lands in Phase 7
 * (see docs/dev-plan.md, ticket 7-1), once :realtime/:session actually have something to
 * connect and tear down.
 */
class CarVoiceInteractionSession(context: Context) : VoiceInteractionSession(context) {

    private var voicePlateView: VoicePlateView? = null

    override fun onCreateContentView(): View =
        VoicePlateView(context).also { voicePlateView = it }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        voicePlateView?.setState(VoicePlateState.LISTENING)
    }

    override fun onHide() {
        // 17節: onHide() と完全な conversation termination を同一視しない。Phase 1 には
        // まだ破棄すべき Realtime セッションが無いため何もしない。Phase 7 で hide 理由を
        // 確認した上で RTC/audio リソースを解放する処理をここに追加する。
        super.onHide()
    }
}
