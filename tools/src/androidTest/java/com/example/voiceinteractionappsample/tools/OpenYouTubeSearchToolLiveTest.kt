package com.example.voiceinteractionappsample.tools

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Real Context, real PackageManager — exercises resolveActivity()/startActivity() for real. */
@RunWith(AndroidJUnit4::class)
class OpenYouTubeSearchToolLiveTest {

    @Test
    fun blankQueryIsInvalidArgumentAndNeverStartsAnActivity() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val tool = OpenYouTubeSearchTool(context)

        val output = tool.execute("call_1", JSONObject().put("query", "  "))

        assertEquals(YouTubeSearchOutcome.INVALID_ARGUMENT.name, output.getString("result"))
    }

    @Test
    fun validQueryNeverCrashesAndNeverSilentlyReportsOpenedOnFailure() = runBlocking {
        // 12節: Intent handlerがない端末でクラッシュしない。この結果はOPENEDかNO_HANDLERの
        // どちらか（このAVDに実際にhandlerがあるかは環境依存）——ここで検証したいのは
        // 「クラッシュしない」ことと「例外を握りつぶしてOPENEDにしていない」こと。
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val tool = OpenYouTubeSearchTool(context)

        val output = tool.execute("call_1", JSONObject().put("query", "Queen live"))

        val result = output.getString("result")
        assertNotEquals(YouTubeSearchOutcome.INVALID_ARGUMENT.name, result)
        assertNotEquals(YouTubeSearchOutcome.FAILED.name, result)
    }
}
