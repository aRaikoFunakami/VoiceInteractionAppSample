package com.example.localvoiceagent.tts

import android.util.Log
import com.example.localvoiceagent.LocalAudioEngine
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

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
    // 発話ごとの世代。speak()/cancel() が進め、古い合成タスクは chunk 境界で自然停止する。
    // 以前は共有の cancelled/speaking フラグ2本で管理していて、(a) speak() が cancelled=false に
    // 戻すと打ち切り済みの旧合成が蘇って旧発話のフレームが新応答の前に混入する、(b) 旧タスクの
    // finally が speaking を下ろして watchdog が新応話の再生前に PLAYING→IDLE へ誤遷移する、の
    // 2つのレースがあった。世代比較 + 実行中タスク数のカウントでどちらも塞ぐ。
    private val generation = AtomicInteger(0)
    private val activeTasks = AtomicInteger(0)

    /** 残 frame（UI/テスト用） */
    fun queuedFrames(): Int = frames.size

    /** TTS が再生待ち audio を持つ or 合成中か（barge-in 判定用、#22） */
    fun isSpeaking(): Boolean = activeTasks.get() > 0 || frames.isNotEmpty()

    /** RenderPipeline の fillFrame に差す。frame があれば埋めて true。 */
    fun fillFrame(buf: ByteBuffer): Boolean {
        val f = frames.poll() ?: return false
        buf.position(0)
        for (i in f.indices) buf.putShort(i * 2, f[i])
        return true
    }

    /**
     * 非同期に合成して queue へ。完了/中断で onDone。
     * 進行中/キュー済みの発話は supersede する（打ち切って新しい発話だけを再生）—
     * 呼び出し側が cancel() を挟み忘れても旧発話と連結されない。
     */
    fun speak(text: String, onDone: (() -> Unit)? = null) {
        val myGen = generation.incrementAndGet()
        // 世代を進めた後に clear するので、旧タスクがこれ以降 put することはない
        // （enqueue は put 前に毎フレーム世代を確認する。境界で最大1フレーム=10ms は残り得る）。
        frames.clear()
        activeTasks.incrementAndGet()
        Log.i(TAG, "speak: \"$text\"")
        worker.execute {
            try {
                if (myGen != generation.get()) return@execute // worker 待ちの間に supersede された
                tts.synthesize(text, object : AudioSink {
                    override fun onAudio(samples: ShortArray, sampleRate: Int, channels: Int) {
                        enqueue(resampleTo48k(samples, sampleRate), myGen)
                    }
                    override fun onEnd() {}
                })
                Log.i(TAG, "speak done")
            } catch (t: Throwable) {
                // パイプライン診断: 元々ここに catch が無く、合成失敗(モデル異常/OOM等)が
                // ログ無しで完全に握りつぶされていた ―「LLMは返事したのにTTSが鳴らない」の
                // 原因になり得る。診断できるようログに残す。
                Log.e(TAG, "speak failed: \"$text\"", t)
            } finally {
                activeTasks.decrementAndGet()
                onDone?.invoke()
            }
        }
    }

    /** barge-in: 未再生 audio を即破棄する（合成中の残りも捨てる）。 */
    fun cancel() {
        generation.incrementAndGet()
        frames.clear()
    }

    fun close() {
        cancel()
        worker.shutdown()
        tts.close()
    }

    private fun enqueue(pcm48k: ShortArray, myGen: Int) {
        var off = 0
        while (off < pcm48k.size && myGen == generation.get()) {
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

    private companion object {
        const val TAG = "TtsPlayer"
    }
}
