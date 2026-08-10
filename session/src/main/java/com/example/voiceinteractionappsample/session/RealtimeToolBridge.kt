package com.example.voiceinteractionappsample.session

import com.example.voiceinteractionappsample.realtime.RealtimeEvent
import com.example.voiceinteractionappsample.tools.ToolCall
import com.example.voiceinteractionappsample.tools.ToolExecutionResult
import org.json.JSONObject

/**
 * Bridges Realtime function-call events (:realtime) and [com.example.voiceinteractionappsample.tools.DeviceToolExecutor]
 * (:tools) — 8-2節.
 *
 * Verified live 2026-08 against a real model-initiated function call: registering a tool in
 * `session.update`'s `tools` array and asking the model to use it produces
 * `response.function_call_arguments.done` with exactly `call_id` / `name` / `arguments`
 * (arguments as a JSON string) — matches what's decoded here, not guessed from docs.
 */
object RealtimeToolBridge {
    fun decodeFunctionCall(event: RealtimeEvent): ToolCall? {
        if (event.type != "response.function_call_arguments.done") return null
        val callId = event.raw.optString("call_id").takeIf { it.isNotEmpty() } ?: return null
        val name = event.raw.optString("name").takeIf { it.isNotEmpty() } ?: return null
        val arguments = event.raw.optString("arguments", "{}")
        return ToolCall(callId, name, arguments)
    }

    /**
     * `conversation.item.create` (function_call_output) followed by `response.create` — 15節:
     * tool結果を返した後にモデル応答を生成させる。[result.output] は成功以外の outcome でも
     * 常に文字列化して返す（14節: 却下理由もモデルへ伝える）。
     */
    fun encodeFunctionCallOutput(result: ToolExecutionResult): List<String> = listOf(
        JSONObject()
            .put("type", "conversation.item.create")
            .put(
                "item",
                JSONObject()
                    .put("type", "function_call_output")
                    .put("call_id", result.callId)
                    .put("output", result.output.toString()),
            )
            .toString(),
        JSONObject().put("type", "response.create").toString(),
    )
}
