package com.example.voiceinteractionappsample.session

import com.example.voiceinteractionappsample.realtime.RealtimeEvent
import com.example.voiceinteractionappsample.tools.ToolExecutionResult
import com.example.voiceinteractionappsample.tools.ToolOutcomeType
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RealtimeToolBridgeTest {

    @Test
    fun decodesRealObservedFunctionCallShape() {
        // 2026-08にライブで実際に観測した response.function_call_arguments.done そのまま。
        val raw = JSONObject(
            """{"type":"response.function_call_arguments.done","event_id":"event_x","response_id":"resp_x",
                "item_id":"item_x","output_index":0,"call_id":"call_9Q9eysr6LstYMEx6",
                "name":"get_weather","arguments":"{\"city\":\"Tokyo\"}"}"""
        )

        val call = RealtimeToolBridge.decodeFunctionCall(RealtimeEvent("response.function_call_arguments.done", raw))

        requireNotNull(call)
        assertEquals("call_9Q9eysr6LstYMEx6", call.callId)
        assertEquals("get_weather", call.name)
        assertEquals("""{"city":"Tokyo"}""", call.argumentsJson)
    }

    @Test
    fun ignoresUnrelatedEventTypes() {
        val event = RealtimeEvent("response.done", JSONObject())

        assertNull(RealtimeToolBridge.decodeFunctionCall(event))
    }

    @Test
    fun encodesFunctionCallOutputThenResponseCreate() {
        val result = ToolExecutionResult("call_1", ToolOutcomeType.SUCCESS, JSONObject().put("ok", true))

        val events = RealtimeToolBridge.encodeFunctionCallOutput(result)

        assertEquals(2, events.size)
        val itemCreate = JSONObject(events[0])
        assertEquals("conversation.item.create", itemCreate.getString("type"))
        assertEquals("function_call_output", itemCreate.getJSONObject("item").getString("type"))
        assertEquals("call_1", itemCreate.getJSONObject("item").getString("call_id"))
        assertEquals("""{"ok":true}""", itemCreate.getJSONObject("item").getString("output"))
        assertEquals("response.create", JSONObject(events[1]).getString("type"))
    }

    @Test
    fun rejectionOutcomesStillGetSentBackToTheModel() {
        // 14節: 却下理由もモデルに伝える — SUCCESS以外を握りつぶさない。
        val result = ToolExecutionResult("call_2", ToolOutcomeType.NOT_ALLOWED, JSONObject().put("error", "blocked"))

        val events = RealtimeToolBridge.encodeFunctionCallOutput(result)

        val output = JSONObject(events[0]).getJSONObject("item").getString("output")
        assertEquals("""{"error":"blocked"}""", output)
    }
}
