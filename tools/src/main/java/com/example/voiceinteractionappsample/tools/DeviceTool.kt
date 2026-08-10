package com.example.voiceinteractionappsample.tools

import org.json.JSONObject

/** A function call the model asked to invoke, decoded from a Realtime `response.function_call_arguments.done` event (8-2節). */
data class ToolCall(val callId: String, val name: String, val argumentsJson: String)

enum class ToolOutcomeType { SUCCESS, NO_HANDLER, INVALID_ARGUMENT, NOT_ALLOWED, FAILED }

/** What gets sent back to the model as `function_call_output` (15節) — every outcome, not just success. */
data class ToolExecutionResult(
    val callId: String,
    val outcome: ToolOutcomeType,
    val output: JSONObject,
)

/**
 * One registrable tool (14節). YouTube (Phase 9) is not vehicle control, but shares this same
 * boundary because Car API tools reuse it later — model output never reaches
 * CarPropertyManager/MediaSession/Navigation/Intent directly, only through [execute] after
 * [checkPolicy]/[checkUxRestriction] have both cleared it.
 */
interface DeviceTool {
    val name: String

    /**
     * Field names this tool's arguments object must contain. A minimal required-field check,
     * not a full JSON Schema validator — a few lines here beats a validation dependency for
     * what this app needs (rung 3).
     */
    val requiredArgumentFields: Set<String>

    /** Null = allowed. Non-null = rejection reason. */
    fun checkPolicy(arguments: JSONObject): String? = null

    /** Null = allowed. Non-null = rejection reason (e.g. driving UX restriction). */
    fun checkUxRestriction(): String? = null

    /** Only called once parse + required-fields + policy + UX have all passed. */
    suspend fun execute(callId: String, arguments: JSONObject): JSONObject
}
