package com.example.localvoiceagent

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * liblocal_audio_engine.so（WebRTC APM/AEC3 ラッパ、fork の local_audio/）の Kotlin 境界。
 * .so は scripts/build_webrtc_android.sh が jniLibs/arm64-v8a/ に配置する（コミットしない）。
 * ネイティブ側は JNI_OnLoad + RegisterNatives で登録（Java_* 命名規約は不使用）。
 *
 * フォーマットは 48kHz / mono / int16 / 10ms = 480 samples 固定（開発計画 §3）。
 * processCapture/processRender は audio thread から呼ぶ前提で、呼び出しごとの
 * ヒープ割り当てなし（DirectByteBuffer を事前確保して使い回すこと）。
 */
object LocalAudioEngine {
    const val SAMPLE_RATE = 48000
    const val FRAME_SAMPLES = 480
    const val FRAME_BYTES = FRAME_SAMPLES * 2

    val loaded: Boolean by lazy {
        try {
            System.loadLibrary("local_audio_engine")
            true
        } catch (e: UnsatisfiedLinkError) {
            false
        }
    }

    fun status(): String =
        if (loaded) "engine: ${nativeGetVersion()}" else "engine: NOT loaded"

    /** 10ms frame 用の DirectByteBuffer（native order）を確保する。 */
    fun newFrameBuffer(): ByteBuffer =
        ByteBuffer.allocateDirect(FRAME_BYTES).order(ByteOrder.nativeOrder())

    fun create(aec: Boolean = true, ns: Boolean = true, agc: Boolean = true): Long {
        check(loaded) { "engine not loaded" }
        return nativeCreate(aec, ns, agc)
    }

    fun destroy(handle: Long) = nativeDestroy(handle)

    /** capture 10ms frame を処理。0 = 成功。input/output は newFrameBuffer() のもの。 */
    fun processCapture(handle: Long, input: ByteBuffer, output: ByteBuffer): Int =
        nativeProcessCapture(handle, input, output)

    /** render(再生) 10ms frame を AEC 参照として投入。0 = 成功。 */
    fun processRender(handle: Long, input: ByteBuffer): Int =
        nativeProcessRender(handle, input)

    fun setStreamDelayMs(handle: Long, delayMs: Int) =
        nativeSetStreamDelayMs(handle, delayMs)

    fun reset(handle: Long) = nativeReset(handle)

    @JvmStatic private external fun nativeGetVersion(): String
    @JvmStatic private external fun nativeCreate(aec: Boolean, ns: Boolean, agc: Boolean): Long
    @JvmStatic private external fun nativeDestroy(handle: Long)
    @JvmStatic private external fun nativeProcessCapture(
        handle: Long, input: ByteBuffer, output: ByteBuffer): Int
    @JvmStatic private external fun nativeProcessRender(handle: Long, input: ByteBuffer): Int
    @JvmStatic private external fun nativeSetStreamDelayMs(handle: Long, delayMs: Int)
    @JvmStatic private external fun nativeReset(handle: Long)

    /**
     * 起動時スモーク: create → render/capture 各 1 frame → reset → destroy。
     * 成功なら処理後 frame の情報を含む文字列を返す。
     */
    fun smokeTest(): String {
        if (!loaded) return "smoke: engine not loaded"
        val h = create()
        if (h == 0L) return "smoke: create failed"
        try {
            val inBuf = newFrameBuffer()
            val outBuf = newFrameBuffer()
            // 1kHz トーンを 1 frame
            for (i in 0 until FRAME_SAMPLES) {
                val v = (2000 * Math.sin(2.0 * Math.PI * 1000.0 * i / SAMPLE_RATE)).toInt()
                inBuf.putShort(i * 2, v.toShort())
            }
            var r = processRender(h, inBuf)
            if (r != 0) return "smoke: render=$r"
            setStreamDelayMs(h, 20)
            r = processCapture(h, inBuf, outBuf)
            if (r != 0) return "smoke: capture=$r"
            reset(h)
            return "smoke: OK (render/capture/reset)"
        } finally {
            destroy(h)
        }
    }
}
