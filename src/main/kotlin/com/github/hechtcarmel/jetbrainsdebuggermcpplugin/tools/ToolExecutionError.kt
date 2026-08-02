package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools

/**
 * A tool failure whose [message] is the complete, client-facing error text.
 *
 * Thrown by the boundary helpers ([AbstractMcpTool.requireSession],
 * [AbstractMcpTool.requirePausedSession], `ToolArguments`) and converted by
 * [AbstractMcpTool.execute] into the standard `isError: true` result. This keeps every
 * precondition check a single expression at the top of `doExecute` instead of a copy-pasted
 * resolve-then-return preamble.
 *
 * The messages carried here are part of the client contract: several ("No active debug session",
 * "Session not found: <id>", "Missing required parameter: <name>") are pinned verbatim by the
 * transport and behaviour tests.
 *
 * Deliberately NOT a [kotlinx.coroutines.CancellationException] subtype, so the
 * cancellation-rethrow convention (see `ThreadingConventionsTest`) never swallows or resurfaces
 * it, and deliberately caught in [AbstractMcpTool.execute] rather than in `McpToolBridge`, so the
 * bridge's broad handler still logs genuinely unexpected exceptions.
 */
class ToolExecutionError(override val message: String) : RuntimeException(message)
