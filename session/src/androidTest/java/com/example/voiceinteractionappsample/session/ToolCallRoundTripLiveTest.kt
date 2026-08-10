package com.example.voiceinteractionappsample.session

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.voiceinteractionappsample.realtime.RealtimeEventCodec
import com.example.voiceinteractionappsample.tools.DeviceTool
import com.example.voiceinteractionappsample.tools.DeviceToolExecutor
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNotNull
import org.junit.Test
import org.junit.runner.RunWith

private class MockWeatherTool : DeviceTool {
    override val name = "get_weather"
    override val requiredArgumentFields = setOf("city")
    override suspend fun execute(callId: String, arguments: JSONObject): JSONObject =
        JSONObject().put("city", arguments.getString("city")).put("forecast", "sunny, 25C")
}

/**
 * Issue #26 (8-2): full round trip against the real API — model calls a registered tool,
 * [RealtimeToolBridge] decodes it, [DeviceToolExecutor] runs it, the result goes back over
 * the DataChannel with the matching call_id, and the model resumes.
 *
 * adb shell am instrument -w -e openaiEphemeralSecret <secret> \
 *   -e class com.example.voiceinteractionappsample.session.ToolCallRoundTripLiveTest \
 *   com.example.voiceinteractionappsample.session.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class ToolCallRoundTripLiveTest {

    @Test
    fun modelInitiatedToolCallExecutesAndModelResumes() = runBlocking {
        val secret = InstrumentationRegistry.getArguments().getString("openaiEphemeralSecret")
        assumeNotNull(secret)

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val connection = RealtimeLiveTestHarness.connectWithMic(context, secret!!)
        val executor = DeviceToolExecutor(listOf(MockWeatherTool()))
        try {
            connection.events.send(
                JSONObject()
                    .put("type", "session.update")
                    .put(
                        "session",
                        JSONObject().put("type", "realtime").put(
                            "tools",
                            JSONArray().put(
                                JSONObject()
                                    .put("type", "function")
                                    .put("name", "get_weather")
                                    .put("description", "Get the current weather for a city")
                                    .put(
                                        "parameters",
                                        JSONObject()
                                            .put("type", "object")
                                            .put("properties", JSONObject().put("city", JSONObject().put("type", "string")))
                                            .put("required", JSONArray().put("city"))
                                            .put("additionalProperties", false),
                                    ),
                            ),
                        ),
                    )
                    .toString()
            )
            RealtimeLiveTestHarness.requestAssistantSpeech(
                connection,
                instructions = "Call the get_weather function with city set to Tokyo. Do not say anything else first.",
            )

            var toolCallHandled = false
            var responseCountAfterToolCall = 0

            withTimeoutOrNull(20_000) {
                connection.events.incoming().collect { json ->
                    val event = RealtimeEventCodec.decode(json)
                    if (!toolCallHandled) {
                        val call = RealtimeToolBridge.decodeFunctionCall(event)
                        if (call != null) {
                            val result = executor.execute(call)
                            RealtimeToolBridge.encodeFunctionCallOutput(result).forEach { connection.events.send(it) }
                            toolCallHandled = true
                        }
                    } else if (event.type == "response.done") {
                        responseCountAfterToolCall++
                    }
                }
            }

            assertTrue("expected the model to actually call get_weather", toolCallHandled)
            assertTrue(
                "expected a response.done AFTER sending function_call_output — the model must " +
                    "resume, not hang waiting for a tool result it already got",
                responseCountAfterToolCall > 0,
            )
        } finally {
            connection.events.close()
            connection.peerConnection.close()
        }
    }
}
