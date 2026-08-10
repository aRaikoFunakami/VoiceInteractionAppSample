package com.example.voiceinteractionappsample.tools

import org.json.JSONObject

/**
 * Parse -> Schema validation -> Local policy -> UX restriction -> Android実行 ->
 * function_call_output (14節). The model's function call never reaches an Android API
 * directly — every registered [DeviceTool] routes through this single pipeline.
 */
class DeviceToolExecutor(tools: List<DeviceTool>) {
    private val toolsByName = tools.associateBy { it.name }

    suspend fun execute(call: ToolCall): ToolExecutionResult {
        val tool = toolsByName[call.name]
            ?: return reject(call.callId, ToolOutcomeType.NO_HANDLER, "no tool registered named '${call.name}'")

        val arguments = try {
            JSONObject(call.argumentsJson)
        } catch (e: Exception) {
            return reject(call.callId, ToolOutcomeType.INVALID_ARGUMENT, "arguments is not valid JSON: ${e.message}")
        }

        val missing = tool.requiredArgumentFields.filterNot { arguments.has(it) }
        if (missing.isNotEmpty()) {
            return reject(call.callId, ToolOutcomeType.INVALID_ARGUMENT, "missing required field(s): $missing")
        }

        tool.checkPolicy(arguments)?.let { reason ->
            return reject(call.callId, ToolOutcomeType.NOT_ALLOWED, reason)
        }
        tool.checkUxRestriction()?.let { reason ->
            return reject(call.callId, ToolOutcomeType.NOT_ALLOWED, reason)
        }

        // 12節/14節: 例外をcatchしてSUCCESSにしてはならない — 失敗はFAILEDとして明示的に返す。
        return try {
            val output = tool.execute(call.callId, arguments)
            ToolExecutionResult(call.callId, ToolOutcomeType.SUCCESS, output)
        } catch (e: Exception) {
            reject(call.callId, ToolOutcomeType.FAILED, e.message ?: e.javaClass.simpleName)
        }
    }

    private fun reject(callId: String, outcome: ToolOutcomeType, reason: String): ToolExecutionResult =
        ToolExecutionResult(callId, outcome, JSONObject().put("error", reason))
}
