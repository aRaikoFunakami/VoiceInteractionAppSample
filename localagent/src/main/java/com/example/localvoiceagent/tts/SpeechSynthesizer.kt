package com.example.localvoiceagent.tts

/**
 * TTS backend 境界(android-local-voice-agent からの移植、issue #48)。
 * 実装は PCM を AudioSink へ渡すだけで、AudioTrack へ直接書かない。
 */
interface AudioSink {
    /** 合成 PCM の通知。samples は int16 mono。複数回呼ばれうる。 */
    fun onAudio(samples: ShortArray, sampleRate: Int, channels: Int)

    /** 合成終了(end-of-stream)。 */
    fun onEnd()
}

interface SpeechSynthesizer {
    /** text を合成し sink へ流す。ブロッキング。worker thread から呼ぶこと。 */
    fun synthesize(text: String, sink: AudioSink)

    fun close()
}
