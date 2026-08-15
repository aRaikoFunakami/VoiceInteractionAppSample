package com.example.localvoiceagent.tts

import com.example.localvoiceagent.LocalAudioEngine
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * TTS 出力を render 経路（processRender → AudioTrack）へ流すための frame 供給源
 * （開発計画 §10: TTS PCM を AudioTrack へ直接書かない）。
 *
 *   TTS native rate (44.1kHz) → 48kHz 線形補間 → 10ms/480 samples framing
 *   → frames queue → RenderPipeline.fillFrame
 *
 * ponytail: 補間は線形（TTS→STT ラウンドトリップで原文一致を確認済みの品質）。
 * 不足が出たら engine 側 PushResampler へ移行。
 */
class TtsPlayer(private val tts: SpeechSynthesizer) {
    // ~20 秒分。合成 worker は満杯時ブロック（put）で背圧をかける
    private val frames = ArrayBlockingQueue<ShortArray>(2000)
    private val worker = Executors.newSingleThreadExecutor()
    private val speaking = AtomicBoolean(false)
    @Volatile private var cancelled = false

    /** 残 frame（UI/テスト用） */
    fun queuedFrames(): Int = frames.size

    /** TTS が再生待ち audio を持つ or 合成中か（barge-in 判定用、#22） */
    fun isSpeaking(): Boolean = speaking.get() || frames.isNotEmpty()

    /** RenderPipeline の fillFrame に差す。frame があれば埋めて true。 */
    fun fillFrame(buf: ByteBuffer): Boolean {
        val f = frames.poll() ?: return false
        buf.position(0)
        for (i in f.indices) buf.putShort(i * 2, f[i])
        return true
    }

    /** 非同期に合成して queue へ。完了/中断で onDone。 */
    fun speak(text: String, onDone: (() -> Unit)? = null) {
        cancelled = false
        speaking.set(true)
        worker.execute {
            try {
                tts.synthesize(text, object : AudioSink {
                    override fun onAudio(samples: ShortArray, sampleRate: Int, channels: Int) {
                        enqueue(resampleTo48k(samples, sampleRate))
                    }
                    override fun onEnd() {}
                })
            } finally {
                speaking.set(false)
                onDone?.invoke()
            }
        }
    }

    /** barge-in: 未再生 audio を即破棄する（合成中の残りも捨てる）。 */
    fun cancel() {
        cancelled = true
        frames.clear()
    }

    fun close() {
        cancel()
        worker.shutdown()
        tts.close()
    }

    private fun enqueue(pcm48k: ShortArray) {
        var off = 0
        while (off < pcm48k.size && !cancelled) {
            val frame = ShortArray(LocalAudioEngine.FRAME_SAMPLES)
            val n = minOf(LocalAudioEngine.FRAME_SAMPLES, pcm48k.size - off)
            System.arraycopy(pcm48k, off, frame, 0, n)  // 末尾は無音 pad
            frames.put(frame)  // 満杯なら背圧（合成 worker のみブロック）
            off += n
        }
    }

    private fun resampleTo48k(pcm: ShortArray, rate: Int): ShortArray {
        if (rate == 48000) return pcm
        val outLen = (pcm.size.toLong() * 48000 / rate).toInt()
        val out = ShortArray(outLen)
        val ratio = rate.toDouble() / 48000.0
        for (i in 0 until outLen) {
            val pos = i * ratio
            val a = pos.toInt().coerceAtMost(pcm.size - 1)
            val b = (a + 1).coerceAtMost(pcm.size - 1)
            val f = pos - a
            out[i] = ((pcm[a] * (1 - f)) + (pcm[b] * f)).toInt().toShort()
        }
        return out
    }
}
