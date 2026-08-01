package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.contract

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.fail
import java.io.File

/**
 * Shared machinery for the golden-snapshot contract tests.
 *
 * A golden file is a *contract with MCP clients*. Changing one is sometimes correct, but it must
 * always be deliberate, so the tests never rewrite themselves silently — regeneration is opt-in
 * via `-Dcontract.update=true` and always fails the run so the diff lands in review.
 */
internal object GoldenFile {

    private const val UPDATE_PROPERTY = "contract.update"

    /**
     * Compares [actual] against the checked-in golden at [sourcePath], or regenerates it when
     * `-Dcontract.update=true` is set.
     */
    fun assertMatches(sourcePath: String, resourcePath: String, actual: String) {
        if (System.getProperty(UPDATE_PROPERTY) == "true") {
            val target = File(sourcePath)
            target.parentFile.mkdirs()
            target.writeText(actual)
            fail("Golden file regenerated at $sourcePath. Review the diff, then re-run without -D$UPDATE_PROPERTY.")
        }

        val expected = readGolden(sourcePath, resourcePath)
        if (expected == actual) return

        val actualFile = File("build/${File(sourcePath).name}.actual")
        actualFile.parentFile.mkdirs()
        actualFile.writeText(actual)

        fail(
            buildString {
                appendLine("The ${File(sourcePath).name} contract changed.")
                appendLine()
                appendLine(describeDifference(expected, actual))
                appendLine()
                appendLine("If this change is intended, regenerate the golden file:")
                appendLine("  ./gradlew test --tests \"*${callerTestName()}\" -Dcontract.update=true")
                appendLine("and treat the diff as the list of breaking changes the release notes owe clients.")
                appendLine("Actual output written to: ${actualFile.path}")
            }
        )
    }

    private fun callerTestName(): String =
        Thread.currentThread().stackTrace
            .firstOrNull { it.className.endsWith("ContractTest") }
            ?.className?.substringAfterLast('.')
            ?: "*ContractTest"

    /**
     * Reads the golden from the test source tree when available, falling back to the packaged
     * resource. The source tree is preferred so a regenerated file is picked up without a
     * `processTestResources` round-trip.
     */
    private fun readGolden(sourcePath: String, resourcePath: String): String {
        val onDisk = File(sourcePath)
        if (onDisk.isFile) return onDisk.readText()
        val stream = GoldenFile::class.java.classLoader.getResourceAsStream(resourcePath)
            ?: fail("Golden file missing from both $sourcePath and classpath:$resourcePath. Regenerate with -D$UPDATE_PROPERTY=true.")
                .let { error("unreachable") }
        return stream.bufferedReader().use { it.readText() }
    }

    /** Reports the first differing line, which is far more actionable than a full-text diff. */
    private fun describeDifference(expected: String, actual: String): String {
        val expectedLines = expected.lines()
        val actualLines = actual.lines()

        val expectedSections = sectionNames(expectedLines)
        val actualSections = sectionNames(actualLines)
        val removed = (expectedSections - actualSections).sorted()
        val added = (actualSections - expectedSections).sorted()

        return buildString {
            if (removed.isNotEmpty()) appendLine("REMOVED entries: ${removed.joinToString(", ")}")
            if (added.isNotEmpty()) appendLine("ADDED entries: ${added.joinToString(", ")}")

            val firstDiff = (0 until maxOf(expectedLines.size, actualLines.size))
                .firstOrNull { expectedLines.getOrNull(it) != actualLines.getOrNull(it) }
            if (firstDiff != null) {
                appendLine("First differing line (${firstDiff + 1}):")
                appendLine("  expected: ${expectedLines.getOrNull(firstDiff) ?: "<end of file>"}")
                appendLine("  actual:   ${actualLines.getOrNull(firstDiff) ?: "<end of file>"}")
            }
        }.ifBlank { "Contents differ." }
    }

    private fun sectionNames(lines: List<String>): Set<String> =
        lines.filter { it.startsWith("## ") }.map { it.removePrefix("## ") }.toSet()

    /**
     * Renders JSON with sorted object keys so the snapshot is stable and line-oriented — a review
     * diff then shows exactly which schema line changed instead of one reflowed blob.
     */
    fun canonicalJson(element: JsonElement, indent: String): String = buildString {
        fun render(node: JsonElement, pad: String) {
            when (node) {
                is JsonObject -> {
                    if (node.isEmpty()) {
                        append("{}")
                        return
                    }
                    appendLine("{")
                    val entries = node.entries.sortedBy { it.key }
                    entries.forEachIndexed { index, (key, value) ->
                        append("$pad  \"$key\": ")
                        render(value, "$pad  ")
                        appendLine(if (index == entries.lastIndex) "" else ",")
                    }
                    append("$pad}")
                }

                is JsonArray -> {
                    if (node.isEmpty()) {
                        append("[]")
                        return
                    }
                    appendLine("[")
                    node.forEachIndexed { index, value ->
                        append("$pad  ")
                        render(value, "$pad  ")
                        appendLine(if (index == node.lastIndex) "" else ",")
                    }
                    append("$pad]")
                }

                is JsonNull -> append("null")
                is JsonPrimitive -> append(if (node.isString) "\"${escape(node.content)}\"" else node.content)
            }
        }
        append(indent)
        render(element, indent)
    }

    private fun escape(text: String): String = text
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")
}
