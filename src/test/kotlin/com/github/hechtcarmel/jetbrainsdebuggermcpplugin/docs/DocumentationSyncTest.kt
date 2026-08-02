package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.docs

import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.ToolRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The docs are a contract too. `tool-manifest.txt` already guards the tool surface against code
 * drift; this test extends the same idea to the human-facing docs, because they have drifted
 * before: `wait_for_pause` shipped as the 4.2.0 headline feature yet was absent from README's
 * tables, and the advertised tool count sat one short — on the Marketplace listing — for several
 * releases.
 *
 * Renaming, adding, or removing a tool without updating README and the bundled skill goes red
 * here instead of shipping silently.
 *
 * Files are read relative to the working directory, which Gradle sets to the project root for
 * tests (the same mechanism `McpConstantsTest` uses for gradle.properties).
 */
class DocumentationSyncTest {

    private val registry = ToolRegistry().apply { registerBuiltInTools() }
    private val toolNames: Set<String> = registry.getAllTools().map { it.name }.toSet()

    private fun read(path: String): String {
        val file = File(path)
        assertTrue("Expected $path relative to the project root (tests run with CWD there)", file.isFile)
        return file.readText()
    }

    /** Table rows whose first cell is a backticked lowercase identifier, e.g. `| `set_breakpoint` | ...`. */
    private fun tableToolNames(markdown: String): Set<String> =
        Regex("""(?m)^\|\s*`([a-z_]+)`""").findAll(markdown).map { it.groupValues[1] }.toSet()

    @Test
    fun `README tool tables list every registered tool and nothing else`() {
        val documented = tableToolNames(read("README.md"))

        val missing = toolNames - documented
        val stale = documented - toolNames

        assertTrue(
            "Tools registered in ToolRegistry but missing from README's tool tables: $missing",
            missing.isEmpty()
        )
        assertTrue(
            "README tool-table rows that match no registered tool (renamed or removed?): $stale",
            stale.isEmpty()
        )
    }

    @Test
    fun `README prose advertises the real tool count`() {
        val readme = read("README.md")
        val counts = Regex("""\*\*(\d+) (?:Comprehensive Tools|MCP tools)\*\*""")
            .findAll(readme)
            .map { it.groupValues[1].toInt() }
            .toList()

        assertTrue(
            "README no longer contains a bolded tool-count claim — update this test's pattern " +
                "so the count stays guarded.",
            counts.isNotEmpty()
        )
        for (count in counts) {
            assertEquals(
                "README advertises $count tools but ToolRegistry registers ${registry.getToolCount()}. " +
                    "Note that one of these claims ships inside the Marketplace description block.",
                registry.getToolCount(),
                count
            )
        }
    }

    @Test
    fun `bundled skill tool reference documents every registered tool and nothing else`() {
        val reference = read("src/main/resources/skill/jetbrains-debugger/references/tool-reference.md")
        val documented = Regex("""(?m)^###\s+`([a-z_]+)`""")
            .findAll(reference)
            .map { it.groupValues[1] }
            .toSet()

        val missing = toolNames - documented
        val stale = documented - toolNames

        assertTrue(
            "Tools registered in ToolRegistry but missing from the skill's tool-reference.md: $missing",
            missing.isEmpty()
        )
        assertTrue(
            "tool-reference.md sections that match no registered tool (renamed or removed?): $stale",
            stale.isEmpty()
        )
    }

    @Test
    fun `bundled skill TRIGGER description names every registered tool`() {
        val skill = read("src/main/resources/skill/jetbrains-debugger/SKILL.md")
        val frontmatterEnd = skill.indexOf("\n---", startIndex = 3)
        assertTrue("SKILL.md has no YAML frontmatter block", skill.startsWith("---") && frontmatterEnd > 0)

        val frontmatter = skill.substring(0, frontmatterEnd)
        val missing = toolNames.filterNot { frontmatter.contains(it) }

        assertTrue(
            "Tools missing from SKILL.md's TRIGGER list, so agents will not activate the skill " +
                "when only those tools are present: $missing",
            missing.isEmpty()
        )
    }
}
