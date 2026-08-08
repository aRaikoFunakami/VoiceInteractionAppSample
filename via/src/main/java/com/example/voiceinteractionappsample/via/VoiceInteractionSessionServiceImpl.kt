package com.example.voiceinteractionappsample.via

import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService

/** Creates the [CarVoiceInteractionSession] that owns Voice Plate + session lifecycle (1節). */
class VoiceInteractionSessionServiceImpl : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession =
        CarVoiceInteractionSession(this)
}
