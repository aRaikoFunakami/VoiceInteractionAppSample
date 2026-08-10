package com.example.voiceinteractionappsample.tools

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class MockTool(
    override val name: String = "mock_tool",
    override val requiredArgumentFields: Set<String> = setOf("query"),
    private val policyRejection: String? = null,
    private val uxRejection: String? = null,
    private val onExecute: suspend (String, JSONObject) -> JSONObject = { _, args ->
        JSONObject().put("received", args.getString("query"))
    },
) : DeviceTool {
    override fun checkPolicy(arguments: JSONObject) = policyRejection
    override fun checkUxRestriction() = uxRejection
    override suspend fun execute(callId: String, arguments: JSONObject) = onExecute(callId, arguments)
}

class DeviceToolExecutorTest {

    @Test
    fun unknownToolNameReturnsNoHandler() = runBlocking {
        val executor = DeviceToolExecutor(listOf(MockTool()))

        val result = executor.execute(ToolCall("call_1", "not_registered", "{}"))

        assertEquals(ToolOutcomeType.NO_HANDLER, result.outcome)
    }

    @Test
    fun malformedJsonArgumentsReturnsInvalidArgument() = runBlocking {
        val executor = DeviceToolExecutor(listOf(MockTool()))

        val result = executor.execute(ToolCall("call_1", "mock_tool", "not json"))

        assertEquals(ToolOutcomeType.INVALID_ARGUMENT, result.outcome)
    }

    @Test
    fun missingRequiredFieldReturnsInvalidArgument() = runBlocking {
        val executor = DeviceToolExecutor(listOf(MockTool()))

        val result = executor.execute(ToolCall("call_1", "mock_tool", "{}"))

        assertEquals(ToolOutcomeType.INVALID_ARGUMENT, result.outcome)
    }

    @Test
    fun policyRejectionReturnsNotAllowedWithoutExecuting() = runBlocking {
        var executed = false
        val tool = MockTool(policyRejection = "blocked by policy", onExecute = { _, _ -> executed = true; JSONObject() })
        val executor = DeviceToolExecutor(listOf(tool))

        val result = executor.execute(ToolCall("call_1", "mock_tool", """{"query":"x"}"""))

        assertEquals(ToolOutcomeType.NOT_ALLOWED, result.outcome)
        assertTrue("execute() must not run once policy rejects", !executed)
    }

    @Test
    fun uxRestrictionReturnsNotAllowedWithoutExecuting() = runBlocking {
        var executed = false
        val tool = MockTool(uxRejection = "driving restriction", onExecute = { _, _ -> executed = true; JSONObject() })
        val executor = DeviceToolExecutor(listOf(tool))

        val result = executor.execute(ToolCall("call_1", "mock_tool", """{"query":"x"}"""))

        assertEquals(ToolOutcomeType.NOT_ALLOWED, result.outcome)
        assertTrue("execute() must not run once UX restriction rejects", !executed)
    }

    @Test
    fun validCallExecutesAndReturnsSuccess() = runBlocking {
        val executor = DeviceToolExecutor(listOf(MockTool()))

        val result = executor.execute(ToolCall("call_1", "mock_tool", """{"query":"hello"}"""))

        assertEquals(ToolOutcomeType.SUCCESS, result.outcome)
        assertEquals("hello", result.output.getString("received"))
    }

    @Test
    fun exceptionDuringExecuteNeverBecomesSuccess() = runBlocking {
        // 12節/14節: これが一番大事な回帰テスト — 例外をcatchしてSUCCESS扱いにしていないか。
        val tool = MockTool(onExecute = { _, _ -> throw IllegalStateException("boom") })
        val executor = DeviceToolExecutor(listOf(tool))

        val result = executor.execute(ToolCall("call_1", "mock_tool", """{"query":"x"}"""))

        assertEquals(ToolOutcomeType.FAILED, result.outcome)
    }

    @Test
    fun callIdIsPreservedThroughEveryOutcome() = runBlocking {
        val executor = DeviceToolExecutor(listOf(MockTool()))

        assertEquals("call_x", executor.execute(ToolCall("call_x", "unknown", "{}")).callId)
        assertEquals("call_x", executor.execute(ToolCall("call_x", "mock_tool", "bad json")).callId)
        assertEquals("call_x", executor.execute(ToolCall("call_x", "mock_tool", """{"query":"q"}""")).callId)
    }
}
