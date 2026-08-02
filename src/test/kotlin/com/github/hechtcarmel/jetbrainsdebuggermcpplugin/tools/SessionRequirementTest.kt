package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools

import com.intellij.openapi.project.Project
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * Pins the session-requirement helpers that replaced ~14 hand-copied preambles (D3): the exact
 * error strings they produce, and that [AbstractMcpTool.execute] converts a [ToolExecutionError]
 * into a standard `isError: true` result rather than letting it escape to the transport.
 *
 * The fixture has no debug session, which is precisely the state these paths exist for. The
 * paused-session branch needs a live session and is therefore pinned at the source level: every
 * tool must pass its historical verb literal, so the wire strings stay greppable and verbatim.
 */
class SessionRequirementTest : BasePlatformTestCase() {

    private class ProbeTool : AbstractMcpTool() {
        override val name = "probe_tool"
        override val description = "Probe for session-requirement behaviour"
        override val inputSchema = buildJsonObject { put("type", "object") }

        var body: (Project, JsonObject) -> CallToolResult = { _, _ -> createSuccessResult("ok") }

        override suspend fun doExecute(project: Project, arguments: JsonObject): CallToolResult =
            body(project, arguments)

        fun requireSessionForTest(project: Project, sessionId: String?, noSessionMessage: String? = null): Unit {
            if (noSessionMessage != null) requireSession(project, sessionId, noSessionMessage)
            else requireSession(project, sessionId)
        }

        fun requirePausedSessionForTest(project: Project, sessionId: String?, verb: String) {
            requirePausedSession(project, sessionId, verb)
        }
    }

    private val tool = ProbeTool()

    private fun errorMessage(block: () -> Unit): String {
        try {
            block()
        } catch (e: ToolExecutionError) {
            return e.message
        }
        throw AssertionError("Expected ToolExecutionError, but nothing was thrown")
    }

    fun `test requireSession without a session keeps the pinned message`() {
        assertEquals(
            "No active debug session",
            errorMessage { tool.requireSessionForTest(project, sessionId = null) }
        )
    }

    fun `test requireSession with an unknown id keeps the pinned message`() {
        assertEquals(
            "Session not found: bogus-id",
            errorMessage { tool.requireSessionForTest(project, sessionId = "bogus-id") }
        )
    }

    fun `test requireSession supports a tool-specific no-session message`() {
        // get_source_context's historical wording
        assertEquals(
            "No active debug session. Provide file_path and line instead.",
            errorMessage {
                tool.requireSessionForTest(
                    project, sessionId = null,
                    noSessionMessage = "No active debug session. Provide file_path and line instead."
                )
            }
        )
    }

    fun `test requirePausedSession resolves the session before the paused check`() {
        // With no session at all, the resolution error wins — same order as the old preambles.
        assertEquals(
            "No active debug session",
            errorMessage { tool.requirePausedSessionForTest(project, sessionId = null, verb = "step over") }
        )
    }

    fun `test execute converts a ToolExecutionError into an isError result`() {
        tool.body = { _, _ -> throw ToolExecutionError("Session must be paused to step over") }
        val result = runBlocking { tool.execute(project, buildJsonObject { }) }

        assertEquals(true, result.isError)
        assertEquals("Session must be paused to step over", (result.content.single() as TextContent).text)
    }

    fun `test execute does not intercept successful results`() {
        tool.body = { _, _ -> CallToolResult(content = listOf(TextContent("fine")), isError = false) }
        val result = runBlocking { tool.execute(project, buildJsonObject { }) }

        assertEquals(false, result.isError)
    }

    /**
     * The paused-branch template plus every call-site verb, pinned at the source level — the
     * live-session paths that would exercise them are unreachable headless (CLAUDE.md "Known
     * gaps"). Together these reconstruct each historical "Session must be paused to <verb>"
     * string verbatim.
     */
    fun `test every requirePausedSession call site passes its historical verb`() {
        val toolsRoot = File("src/main/kotlin/com/github/hechtcarmel/jetbrainsdebuggermcpplugin/tools")

        assertTrue(
            "AbstractMcpTool must build the pinned message from the verb",
            File(toolsRoot, "AbstractMcpTool.kt").readText()
                .contains("\"Session must be paused to \$verb\"")
        )

        mapOf(
            "execution/RunToLineTool.kt" to "run to line",
            "evaluation/EvaluateTool.kt" to "evaluate expressions",
            "variable/GetVariablesTool.kt" to "get variables",
            "variable/SetVariableTool.kt" to "modify variables",
            "stack/GetStackTraceTool.kt" to "get stack trace",
            "stack/SelectStackFrameTool.kt" to "select stack frame",
            "stack/ListThreadsTool.kt" to "list threads",
        ).forEach { (path, verb) ->
            assertTrue(
                "$path must call requirePausedSession with the verb \"$verb\" — the resulting " +
                    "\"Session must be paused to $verb\" is the client contract",
                File(toolsRoot, path).readText().contains("requirePausedSession(project, sessionId, \"$verb\")")
            )
        }

        // The five collapsed execution tools keep their pause-state strings as literals.
        mapOf(
            "execution/StepOverTool.kt" to "Session must be paused to step over",
            "execution/StepIntoTool.kt" to "Session must be paused to step into",
            "execution/StepOutTool.kt" to "Session must be paused to step out",
            "execution/ResumeTool.kt" to "Session is not paused",
            "execution/PauseTool.kt" to "Session is already paused",
        ).forEach { (path, message) ->
            assertTrue(
                "$path must keep its pinned pause-state message \"$message\"",
                File(toolsRoot, path).readText().contains("\"$message\"")
            )
        }
    }
}
