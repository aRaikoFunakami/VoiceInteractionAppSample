package com.example.voiceinteractionappsample.via

import android.service.voice.VoiceInteractionService

/**
 * Minimal always-on VIA entry point (1節).
 *
 * This class MUST stay this small. PeerConnection, HTTP clients, the OpenAI connection,
 * audio processing, tool execution and UI state machines live under :session / :realtime /
 * :audio / :tools, reached via [CarVoiceInteractionSession] once a session starts — never here.
 */
class VoiceInteractionServiceImpl : VoiceInteractionService()
