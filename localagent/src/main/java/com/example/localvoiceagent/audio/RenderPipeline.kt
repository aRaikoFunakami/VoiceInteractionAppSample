package com.example.localvoiceagent.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import android.util.Log
import com.example.localvoiceagent.LocalAudioEngine
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Render パイプライン(android-local-voice-agent からの移植、issue #48):
 *   frame source → APM ProcessReverseStream(AEC 参照)→ AudioTrack → Speaker
 *
 * 再生する PCM は必ず先に processRender へ通す(AudioTrack 直接書き込み禁止)。
 * 無音 frame も reverse stream へ投入し続け、AEC の render 経路を実再生と常に一致させる。
 *
 * 移植時の変更(docs/local-voice-agent-dev-plan.md §3.3):
 * - dump/onFramePlayed 系を削除(デバッグ専用、acceptance-checklist #18)
 * - USAGE_MEDIA → USAGE_ASSISTANT(AAOS の音声アシスタント経路。CarAudioContext は
 *   VOICE_COMMAND になり、focus は controller 側が管理する)
 * - render スレッドを THREAD_PRIORITY_URGENT_AUDIO に、ループを try/catch で保護
 * - stop() が join 成否を返す(タイムアウト時は capture 側が APM 破棄をスキップする)
 */
class RenderPipeline(
    private val engineHandle: () -> Long,
    private val fillFrame: (ByteBuffer) -> Boolean,
) {
    val framesRendered = AtomicLong()
    val processErrors = AtomicLong()
    val writeErrors = AtomicLong()

    private val running = AtomicBoolean(false)
    private var thread: Thread? = null
    private var track: AudioTrack? = null

    /** AudioTrack 由来の underrun 回数。 */
    fun underrunCount(): Int = track?.underrunCount ?: 0

    fun start(): Boolean {
        if (running.getAndSet(true)) return true
        val minBuf = AudioTrack.getMinBufferSize(
            LocalAudioEngine.SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(LocalAudioEngine.SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
            )
            .setBufferSizeInBytes(maxOf(minBuf * 2, LocalAudioEngine.FRAME_BYTES * 8))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        if (t.state != AudioTrack.STATE_INITIALIZED) {
            t.release()
            running.set(false)
            return false
        }
        track = t

        thread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val frame = LocalAudioEngine.newFrameBuffer()
            t.play()
            while (running.get()) {
                try {
                    frame.clear()
                    if (!fillFrame(frame)) {
                        for (i in 0 until LocalAudioEngine.FRAME_SAMPLES) {
                            frame.putShort(i * 2, 0)
                        }
                    }
                    val h = engineHandle()
                    if (h != 0L) {
                        if (LocalAudioEngine.processRender(h, frame) != 0) {
                            processErrors.incrementAndGet()
                        }
                    }
                    frame.position(0)
                    val n = t.write(frame, LocalAudioEngine.FRAME_BYTES, AudioTrack.WRITE_BLOCKING)
                    if (n != LocalAudioEngine.FRAME_BYTES) {
                        writeErrors.incrementAndGet()
                    } else {
                        framesRendered.incrementAndGet()
                    }
                } catch (tr: Throwable) {
                    writeErrors.incrementAndGet()
                    Log.e(TAG, "render loop error", tr)
                }
            }
            t.stop()
            t.release()
            track = null
        }, "render").apply { start() }
        return true
    }

    /** @return render スレッドの join に成功したか(false なら APM 破棄をスキップすること)。 */
    fun stop(): Boolean {
        if (!running.getAndSet(false)) return true
        thread?.join(2000)
        val joined = thread?.isAlive != true
        thread = null
        if (!joined) Log.w(TAG, "render thread did not join in 2s")
        return joined
    }

    private companion object {
        const val TAG = "RenderPipeline"
    }
}
