package com.example.voiceinteractionappsample.audio

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/** All three AEC modes (5-1節) must construct a working module — exercises the real JNI binding. */
@RunWith(AndroidJUnit4::class)
class WebRtcAudioEngineAecModeTest {

    @Test
    fun allThreeModesConstructSuccessfully() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        for (mode in AecMode.entries) {
            val module = WebRtcAudioEngine.create(context, aecMode = mode)
            assertNotNull("AecMode.$mode should produce a module", module)
            module.release()
        }
    }
}
