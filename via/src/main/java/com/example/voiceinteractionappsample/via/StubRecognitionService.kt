package com.example.voiceinteractionappsample.via

import android.content.Intent
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

/**
 * Manifest-only placeholder.
 *
 * AOSP's `VoiceInteractionServiceInfo` parser treats `recognitionService` as mandatory —
 * omitting it leaves the whole VIA registration unparsed (getParseError() non-null), not just
 * disables recognition. This app's audio path is Realtime WebRTC only (0節); this service
 * exists solely to satisfy that manifest requirement and MUST NEVER be wired to the
 * microphone — running it alongside the Realtime audio path would double-record (1節).
 */
class StubRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        listener?.error(SpeechRecognizer.ERROR_CLIENT)
    }

    override fun onCancel(listener: Callback?) = Unit

    override fun onStopListening(listener: Callback?) = Unit
}
