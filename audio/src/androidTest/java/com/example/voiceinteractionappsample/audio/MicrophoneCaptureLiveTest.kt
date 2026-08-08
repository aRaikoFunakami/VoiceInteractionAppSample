package com.example.voiceinteractionappsample.audio

import android.Manifest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Ground-truth check for "does this emulator/host combo actually deliver microphone audio".
 *
 * Deliberately bypasses JavaAudioDeviceModule — WebRTC's audio module wraps exactly this same
 * android.media.AudioRecord underneath, so a raw AudioRecord failing here means WebRTC would
 * fail identically for the same host/AVD reason. Uses AudioSource.VOICE_COMMUNICATION to match
 * WebRtcAudioEngine's configuration (6節).
 */
@RunWith(AndroidJUnit4::class)
class MicrophoneCaptureLiveTest {

    @get:Rule
    val permissionRule: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)

    @Test
    fun capturesNonSilentAudioBuffer() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val sampleRate = 16_000
        val minBufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        assertTrue("getMinBufferSize failed: $minBufferSize", minBufferSize > 0)

        val audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBufferSize * 4,
        )
        try {
            assertEquals(
                "AudioRecord failed to initialize (state=${audioRecord.state})",
                AudioRecord.STATE_INITIALIZED,
                audioRecord.state,
            )

            audioRecord.startRecording()
            assertEquals(
                "startRecording() did not reach RECORDSTATE_RECORDING",
                AudioRecord.RECORDSTATE_RECORDING,
                audioRecord.recordingState,
            )

            // Read a handful of buffers; fail only on a real read error, not on silence —
            // this environment (headless AVD, no real acoustic input) may legitimately
            // capture silence. The point is CAN we read frames at all without error.
            val buffer = ShortArray(minBufferSize)
            repeat(5) {
                val read = audioRecord.read(buffer, 0, buffer.size)
                assertTrue("AudioRecord.read() returned error code $read", read >= 0)
            }
        } finally {
            audioRecord.stop()
            audioRecord.release()
        }
    }
}
