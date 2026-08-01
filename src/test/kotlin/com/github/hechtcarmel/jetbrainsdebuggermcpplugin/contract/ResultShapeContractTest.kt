package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.contract

import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.history.CommandEntryExport
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.server.models.ContentBlock
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.server.models.ToolAnnotations
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.server.models.ToolDefinition
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.breakpoint.BreakpointListResult
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.models.BreakpointHitInfo
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.models.BreakpointInfo
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.models.DebugSessionInfo
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.models.DebugSessionStatus
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.models.EvaluateResponse
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.models.EvaluationResult
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.models.ExecutionControlResult
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.models.RemoveBreakpointResult
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.models.RunConfigurationInfo
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.models.RunConfigurationListResult
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.models.RunConfigurationResult
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.models.SelectFrameResult
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.models.SetBreakpointResult
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.models.SetVariableResult
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.models.SourceContext
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.models.SourceLine
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.models.SourceLocation
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.models.StackFrameInfo
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.models.StackTraceResult
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.models.StopSessionResult
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.models.ThreadInfo
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.models.ThreadListResult
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.models.VariableInfo
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.models.VariablesResult
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.models.WaitForPauseResult
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.serializer
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Golden snapshot of the MCP tool RESPONSE surface: the wire key, JSON kind, nullability and
 * optionality of every field of every serializable result model, plus every enum's wire values.
 *
 * This is a *contract with MCP clients* and it is unusually fragile, because these models use
 * plain Kotlin property names as their wire keys — there is exactly one `@SerialName` in all of
 * `src/main`. Renaming `VariableInfo.value` to `.displayValue` is therefore a source-compatible
 * refactor that an IDE will perform silently and that breaks every client parsing a
 * `get_variables` response. Nothing else in this suite notices: [ToolManifestContractTest] pins
 * tool INPUTS only, and the pre-existing model tests assert with substring `contains` checks,
 * which stay green even if every nullable and every default is dropped.
 *
 * Shapes are read from each serializer's [SerialDescriptor] rather than from a hand-populated
 * instance. That covers every field by construction, so a newly added property cannot be missed
 * by forgetting to set it — the failure mode of the populate-an-instance approach.
 *
 * The models are enumerated by hand in [PINNED_SHAPES] rather than discovered by scanning the
 * classpath: a discovery bug silently shrinks coverage, whereas an omission from a hand-written
 * list is visible in review. [testPinnedListCoversTheResultSurface] guards the list's size.
 *
 * Regenerate deliberately with:
 * ```
 * ./gradlew test --tests "*ResultShapeContractTest" -Dcontract.update=true
 * ```
 */
@OptIn(ExperimentalSerializationApi::class)
class ResultShapeContractTest {

    private companion object {
        const val GOLDEN_RESOURCE = "contract/result-shapes.txt"
        const val GOLDEN_SOURCE_PATH = "src/test/resources/contract/result-shapes.txt"

        /**
         * Lower bound on pinned models. Deliberately below the actual count so adding a model
         * does not force a churn edit here, while a refactor that guts the list still fails.
         */
        const val MIN_PINNED_SHAPES = 25

        val PINNED_SHAPES: List<SerialDescriptor> = listOf(
            // Protocol envelope — what every tools/call response is wrapped in.
            serializer<ToolCallResult>().descriptor,
            serializer<ContentBlock.Text>().descriptor,
            serializer<ToolDefinition>().descriptor,
            serializer<ToolAnnotations>().descriptor,

            // Breakpoints
            serializer<BreakpointInfo>().descriptor,
            serializer<BreakpointListResult>().descriptor,
            serializer<SetBreakpointResult>().descriptor,
            serializer<RemoveBreakpointResult>().descriptor,

            // Sessions
            serializer<DebugSessionInfo>().descriptor,
            serializer<DebugSessionStatus>().descriptor,
            serializer<BreakpointHitInfo>().descriptor,
            serializer<SourceLocation>().descriptor,
            serializer<SourceContext>().descriptor,
            serializer<SourceLine>().descriptor,
            serializer<WaitForPauseResult>().descriptor,
            serializer<StopSessionResult>().descriptor,

            // Stack and threads
            serializer<StackFrameInfo>().descriptor,
            serializer<ThreadInfo>().descriptor,
            serializer<StackTraceResult>().descriptor,
            serializer<ThreadListResult>().descriptor,
            serializer<SelectFrameResult>().descriptor,

            // Variables
            serializer<VariableInfo>().descriptor,
            serializer<VariablesResult>().descriptor,
            serializer<SetVariableResult>().descriptor,

            // Evaluation
            serializer<EvaluationResult>().descriptor,
            serializer<EvaluateResponse>().descriptor,

            // Run configurations
            serializer<RunConfigurationInfo>().descriptor,
            serializer<RunConfigurationListResult>().descriptor,
            serializer<RunConfigurationResult>().descriptor,
            serializer<ExecutionControlResult>().descriptor,

            // Command history — written to disk by the tool window's Export action, so a
            // user-facing shape too. CommandEntry/CommandStatus are deliberately absent: they
            // are not @Serializable, and CommandEntryExport is the only form that reaches a file.
            serializer<CommandEntryExport>().descriptor,
        )
    }

    @Test
    fun `result shapes match golden snapshot`() {
        GoldenFile.assertMatches(GOLDEN_SOURCE_PATH, GOLDEN_RESOURCE, renderShapes())
    }

    @Test
    fun `pinned list covers the result surface`() {
        assertTrue(
            "Only ${PINNED_SHAPES.size} shapes pinned — the list has been gutted. " +
                "Every @Serializable model reachable from a tool result belongs here.",
            PINNED_SHAPES.size >= MIN_PINNED_SHAPES
        )
    }

    @Test
    fun `pinned list has no duplicates`() {
        val names = PINNED_SHAPES.map { it.serialName }
        assertTrue(
            "Duplicate entries in PINNED_SHAPES: ${names.groupBy { it }.filterValues { it.size > 1 }.keys}",
            names.size == names.distinct().size
        )
    }

    private fun renderShapes(): String {
        val sorted = PINNED_SHAPES.sortedBy { it.serialName }
        return buildString {
            appendLine("# MCP result shapes — golden snapshot")
            appendLine("# Regenerate: ./gradlew test --tests \"*ResultShapeContractTest\" -Dcontract.update=true")
            appendLine("#")
            appendLine("# Wire keys are plain Kotlin property names — renaming a property IS a breaking")
            appendLine("# change for every MCP client. 'optional' means the property has a Kotlin default;")
            appendLine("# production serializes with encodeDefaults=true, so optional fields are still")
            appendLine("# emitted. StreamableHttpTransportTest pins the bytes actually sent to a client.")
            appendLine("# shapes: ${sorted.size}")

            sorted.forEach { descriptor ->
                appendLine()
                appendLine("## ${descriptor.serialName}")
                appendLine("kind: ${descriptor.kind}")

                if (descriptor.kind == StructureKind.CLASS || descriptor.kind == StructureKind.OBJECT) {
                    (0 until descriptor.elementsCount).forEach { index ->
                        val element = descriptor.getElementDescriptor(index)
                        val flags = buildList {
                            if (element.isNullable) add("nullable")
                            if (descriptor.isElementOptional(index)) add("optional")
                        }.joinToString(",").ifEmpty { "required,non-null" }
                        appendLine("  ${descriptor.getElementName(index)}: ${element.kind} [$flags]")
                    }
                } else {
                    // Enums: the wire values are the contract.
                    (0 until descriptor.elementsCount).forEach { index ->
                        appendLine("  value: ${descriptor.getElementName(index)}")
                    }
                }
            }
        }
    }
}
