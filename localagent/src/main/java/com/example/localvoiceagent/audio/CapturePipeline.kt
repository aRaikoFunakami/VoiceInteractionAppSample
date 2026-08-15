package com.example.localvoiceagent.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import android.util.Log
import com.example.localvoiceagent.LocalAudioEngine
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Capture パイプライン(android-local-voice-agent からの移植、issue #48):
 *   AudioRecord(48kHz mono, VOICE_RECOGNITION) → 10ms frame → APM ProcessStream → clean PCM を consumer へ
 *
 * 移植時の変更(docs/local-voice-agent-dev-plan.md §3.3):
 * - dump/preProcess 系を削除(生音声の永続化は acceptance-checklist #18 違反。デバッグ専用機能で
 *   呼び出し元も移植対象外)
 * - engineHandle を @Volatile 化(render スレッドから読まれる Long の tearing 対策)
 * - capture スレッドを THREAD_PRIORITY_URGENT_AUDIO に
 * - ループ本体を try/catch で保護(音声スレッドの未捕捉例外はプロセスごと落とすため)
 * - stop(destroyEngine): render 側の join がタイムアウトした場合に APM ハンドル破棄を
 *   スキップできるようにする(破棄よりリークを選ぶ。§3.3 R16)
 */
class CapturePipeline(
    private val onCleanFrame: ((ByteBuffer) -> Unit)? = null,
) {
    val framesProcessed = AtomicLong()
    val readErrors = AtomicLong()
    val processErrors = AtomicLong()

    private val running = AtomicBoolean(false)
    private var thread: Thread? = null
    @Volatile private var engineHandle = 0L

    @SuppressLint("MissingPermission") // RECORD_AUDIO は呼び出し側が取得済み
    fun start(aec: Boolean = true, ns: Boolean = true, agc: Boolean = true): Boolean {
        if (running.getAndSet(true)) return true
        engineHandle = LocalAudioEngine.create(aec, ns, agc)
        if (engineHandle == 0L) {
            running.set(false)
            return false
        }

        val minBuf = AudioRecord.getMinBufferSize(
            LocalAudioEngine.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            LocalAudioEngine.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf * 2, LocalAudioEngine.FRAME_BYTES * 8),
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            LocalAudioEngine.destroy(engineHandle)
            engineHandle = 0L
            running.set(false)
            return false
        }

        thread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val inBuf = LocalAudioEngine.newFrameBuffer()
            val outBuf = LocalAudioEngine.newFrameBuffer()
            record.startRecording()
            while (running.get()) {
                try {
                    inBuf.clear()
                    val n = record.read(
                        inBuf,
                        LocalAudioEngine.FRAME_BYTES,
                        AudioRecord.READ_BLOCKING,
                    )
                    if (n != LocalAudioEngine.FRAME_BYTES) {
                        readErrors.incrementAndGet()
                        continue
                    }
                    val r = LocalAudioEngine.processCapture(engineHandle, inBuf, outBuf)
                    if (r != 0) {
                        processErrors.incrementAndGet()
                        continue
                    }
                    framesProcessed.incrementAndGet()
                    onCleanFrame?.invoke(outBuf)
                } catch (t: Throwable) {
                    // 音声スレッドの未捕捉例外は KillApplicationHandler がプロセスを落とす。
                    // カウントして継続し、エラー状態の表面化は上位(controller)に任せる。
                    readErrors.incrementAndGet()
                    Log.e(TAG, "capture loop error", t)
                }
            }
            record.stop()
            record.release()
        }, "capture").apply { start() }
        return true
    }

    /** render 側が同じ APM を共有するためのハンドル(1 エンジン 2 方向が AEC の前提)。 */
    fun engineHandle(): Long = engineHandle

    fun setStreamDelayMs(ms: Int) {
        if (engineHandle != 0L) LocalAudioEngine.setStreamDelayMs(engineHandle, ms)
    }

    /**
     * @param destroyEngine false なら APM ハンドルを破棄せず残す(render スレッドの join が
     *   タイムアウトし、まだハンドルに触れている可能性がある場合に呼び出し側が指定する)。
     * @return capture スレッドの join に成功したか。
     */
    fun stop(destroyEngine: Boolean = true): Boolean {
        if (!running.getAndSet(false)) return true
        thread?.join(2000)
        val joined = thread?.isAlive != true
        thread = null
        if (engineHandle != 0L) {
            if (destroyEngine && joined) {
                LocalAudioEngine.destroy(engineHandle)
                engineHandle = 0L
            } else {
                // ponytail: 破棄を諦めて ~1MB の APM をリークする。クラッシュより安い。
                Log.w(TAG, "skip engine destroy (destroyEngine=$destroyEngine joined=$joined)")
            }
        }
        return joined
    }

    private companion object {
        const val TAG = "CapturePipeline"
    }
}
