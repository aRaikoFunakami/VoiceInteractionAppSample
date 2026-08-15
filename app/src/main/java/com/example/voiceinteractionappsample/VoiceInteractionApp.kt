package com.example.voiceinteractionappsample

import android.app.Application
import com.example.voiceinteractionappsample.localagent.LocalAgentRuntime

/**
 * issue #49: LOCAL_AGENT の常駐 LLM(数 GB)をメモリ逼迫時に解放するための配線。
 * VoiceInteractionService は常時バインドされるためプロセスは生き続ける — LMK に
 * 強制終了される前に能動的に手放し、次回 PTT で再ロードさせる(計画書 §3.4)。
 */
class VoiceInteractionApp : Application() {
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        LocalAgentRuntime.onTrimMemory(level)
    }
}
