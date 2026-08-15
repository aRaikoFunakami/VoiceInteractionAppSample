package com.example.localvoiceagent.stt

/**
 * STT backend 境界(android-local-voice-agent からの移植、issue #48)。
 * acceptAudio は audio thread から呼ばれるため、実装は内部 queue に積むだけで
 * inference は専用 worker で行うこと。
 */
interface SpeechRecognizer {
    /** AEC 済み 48kHz mono int16 PCM を渡す。配列は呼び出し側が使い回さないこと(参照が保持される)。 */
    fun acceptAudio(samples: ShortArray, sampleRate: Int)

    /** 認識セグメント確定時のコールバック(worker thread 上で呼ばれる)。 */
    var onFinalResult: ((String) -> Unit)?

    /** 発話中(VAD が speech を検出中)か。barge-in 判定に使う。 */
    fun isSpeechActive(): Boolean

    fun reset()
    fun close()
}
