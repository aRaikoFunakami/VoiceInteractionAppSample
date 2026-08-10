package com.example.voiceinteractionappsample.session

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.voiceinteractionappsample.realtime.RealtimeConnection
import com.example.voiceinteractionappsample.realtime.RealtimeEventCodec
import com.example.voiceinteractionappsample.tools.OpenYouTubeSearchToolSchema
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 25節 acceptance criteria (Issue #32, 10-3): does the model actually decide to call
 * open_youtube_search for a real "I want to watch a video" request, and NOT call it for a
 * purely informational question about the same subject — the tool description's whole job
 * (11節).
 *
 * A synthetic text user turn (`conversation.item.create`) stands in for real speech here —
 * this tests the model's tool-selection behavior specifically, not speech recognition (that's
 * already covered elsewhere).
 *
 * adb shell am instrument -w -e openaiEphemeralSecret <secret> \
 *   -e class com.example.voiceinteractionappsample.session.YouTubeToolTriggerLiveTest \
 *   com.example.voiceinteractionappsample.session.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class YouTubeToolTriggerLiveTest {

    @Test
    fun watchRequestCallsToolButInfoQuestionDoesNot() = runBlocking {
        val secret = InstrumentationRegistry.getArguments().getString("openaiEphemeralSecret")
        assumeNotNull(secret)

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val connection = RealtimeLiveTestHarness.connectWithMic(context, secret!!)
        try {
            connection.events.send(
                JSONObject()
                    .put("type", "session.update")
                    .put(
                        "session",
                        JSONObject().put("type", "realtime")
                            .put("tools", JSONArray().put(OpenYouTubeSearchToolSchema.toJson())),
                    )
                    .toString()
            )

            val watchRequestCalledTool = askAndCheckIfToolWasCalled(
                connection,
                userText = "I want to watch Queen's live performance video.",
            )
            val infoQuestionCalledTool = askAndCheckIfToolWasCalled(
                connection,
                userText = "Can you tell me some interesting facts about the band Queen?",
            )

            assertTrue("expected a watch request to call open_youtube_search", watchRequestCalledTool)
            assertFalse("expected an informational question to NOT call open_youtube_search", infoQuestionCalledTool)
        } finally {
            connection.events.close()
            connection.peerConnection.close()
        }
    }

    private suspend fun askAndCheckIfToolWasCalled(
        connection: RealtimeConnection,
        userText: String,
    ): Boolean {
        connection.events.send(
            JSONObject()
                .put("type", "conversation.item.create")
                .put(
                    "item",
                    JSONObject()
                        .put("type", "message")
                        .put("role", "user")
                        .put(
                            "content",
                            JSONArray().put(JSONObject().put("type", "input_text").put("text", userText)),
                        ),
                )
                .toString()
        )
        connection.events.send(JSONObject().put("type", "response.create").toString())

        // Fixed window, no early-exit-on-response.done trickery (a manually thrown
        // CancellationException inside collect{} would cancel withTimeoutOrNull's own coroutine
        // in a way that isn't guaranteed to just return null cleanly) — simpler and already
        // proven safe in every other live test this session.
        var toolCalled = false
        withTimeoutOrNull(15_000) {
            connection.events.incoming().collect { json ->
                val event = RealtimeEventCodec.decode(json)
                if (event.type == "response.function_call_arguments.done" &&
                    event.raw.optString("name") == OpenYouTubeSearchToolSchema.NAME
                ) {
                    toolCalled = true
                }
            }
        }
        return toolCalled
    }
}
