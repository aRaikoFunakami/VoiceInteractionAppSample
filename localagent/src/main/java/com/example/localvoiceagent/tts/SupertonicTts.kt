package com.example.localvoiceagent.tts

import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsSupertonicModelConfig
import java.io.File

/**
 * supertonic-3-ja(sherpa-onnx の日本語対応 TTS、int8)
 * (android-local-voice-agent からの移植、issue #48)。
 * モデルは scripts/fetch_supertonic.sh が /data/local/tmp/tts/ へ push する。
 *
 * 移植時の変更: warmUp() 追加(初回 speak() でのモデルロード遅延を
 * LocalAgentRuntime.ensureInitialized() へ先出しする)。
 */
class SupertonicTts : SpeechSynthesizer {
    companion object {
        private const val DIR =
            "/data/local/tmp/tts/sherpa-onnx-supertonic-3-tts-int8-2026-05-11"

        fun modelAvailable(): Boolean = File("$DIR/tts.json").canRead()
    }

    private var tts: OfflineTts? = null

    private fun engine(): OfflineTts = tts ?: OfflineTts(
        null,
        OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                supertonic = OfflineTtsSupertonicModelConfig(
                    durationPredictor = "$DIR/duration_predictor.int8.onnx",
                    textEncoder = "$DIR/text_encoder.int8.onnx",
                    vectorEstimator = "$DIR/vector_estimator.int8.onnx",
                    vocoder = "$DIR/vocoder.int8.onnx",
                    ttsJson = "$DIR/tts.json",
                    unicodeIndexer = "$DIR/unicode_indexer.bin",
                    voiceStyle = "$DIR/voice.bin",
                ),
                numThreads = 2,
            ),
        ),
    ).also { tts = it }

    /** モデルロード + onnx セッション初期化の先出し(捨て合成 1 回)。 */
    fun warmUp() {
        engine().generateWithConfig(
            "あ",
            GenerationConfig(sid = 0, numSteps = 8, extra = mapOf("lang" to "ja")),
        )
    }

    override fun synthesize(text: String, sink: AudioSink) {
        val audio = engine().generateWithConfig(
            text,
            GenerationConfig(sid = 0, numSteps = 8, extra = mapOf("lang" to "ja")),
        )
        // float [-1,1] → int16(パイプライン基準フォーマット)
        val pcm = ShortArray(audio.samples.size) { i ->
            (audio.samples[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
        }
        sink.onAudio(pcm, audio.sampleRate, 1)
        sink.onEnd()
    }

    override fun close() {
        tts?.release()
        tts = null
    }
}
