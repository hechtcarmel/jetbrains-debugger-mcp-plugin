package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.util

import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.McpConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipFile

/**
 * Pins the durability of the bundled companion skill.
 *
 * `SkillInstaller.SKILL_FILES` is a hand-maintained list: adding a file under
 * `src/main/resources/skill/` without updating it would silently ship a skill with dangling
 * links, and removing a resource without updating it would make installation fail at runtime.
 * The first test makes both mistakes fail the build instead.
 */
class SkillInstallerTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val resourceDir = File("src/main/resources/skill/jetbrains-debugger")

    @Test
    fun `SKILL_FILES matches the bundled resource directory exactly`() {
        assertTrue(
            "Expected skill resources at ${resourceDir.path} relative to the project root",
            resourceDir.isDirectory
        )

        val onDisk = resourceDir.walkTopDown()
            .filter { it.isFile }
            .map { it.relativeTo(resourceDir).invariantSeparatorsPath }
            .toSortedSet()

        assertEquals(
            "SkillInstaller.SKILL_FILES is hand-maintained and must list exactly the files under " +
                "${resourceDir.path} — otherwise the installed skill silently drifts from the " +
                "bundled resources.",
            onDisk,
            SkillInstaller.SKILL_FILES.toSortedSet()
        )
    }

    @Test
    fun `every listed skill file is readable from the classpath`() {
        for (relativePath in SkillInstaller.SKILL_FILES) {
            val stream = SkillInstaller::class.java
                .getResourceAsStream("/skill/jetbrains-debugger/$relativePath")
            assertNotNull("Skill resource missing from classpath: $relativePath", stream)
            stream!!.close()
        }
    }

    @Test
    fun `installToDirectory stamps the plugin version into the SKILL frontmatter`() {
        val skillDir = SkillInstaller.installToDirectory(temp.newFolder())

        assertNotNull("installToDirectory failed", skillDir)
        val installed = File(skillDir!!, "SKILL.md").readText()

        assertTrue(
            "Installed SKILL.md must carry a version stamp so on-disk copies have a staleness signal",
            installed.startsWith("---\nversion: ${McpConstants.SERVER_VERSION}\n")
        )
        // The stamp is a pure insertion: everything else must be byte-identical to the resource.
        val original = resourceDir.resolve("SKILL.md").readText()
        assertEquals(
            original.replaceFirst("---\n", "---\nversion: ${McpConstants.SERVER_VERSION}\n"),
            installed
        )
    }

    @Test
    fun `installToDirectory copies non-manifest files unmodified`() {
        val skillDir = SkillInstaller.installToDirectory(temp.newFolder())

        assertNotNull("installToDirectory failed", skillDir)
        val installed = File(skillDir!!, "references/tool-reference.md")
        assertTrue("tool-reference.md was not installed", installed.isFile)
        assertEquals(
            resourceDir.resolve("references/tool-reference.md").readText(),
            installed.readText()
        )
    }

    @Test
    fun `writeZip packages every skill file under the skill name with the same stamp`() {
        val zipFile = temp.newFile("skill.zip")

        assertTrue("writeZip failed", SkillInstaller.writeZip(zipFile))

        ZipFile(zipFile).use { zip ->
            val entryNames = zip.entries().asSequence().map { it.name }.toSortedSet()
            assertEquals(
                SkillInstaller.SKILL_FILES.map { "jetbrains-debugger/$it" }.toSortedSet(),
                entryNames
            )

            val manifest = zip.getInputStream(zip.getEntry("jetbrains-debugger/SKILL.md"))
                .bufferedReader().use { it.readText() }
            assertTrue(
                "Zipped SKILL.md must carry the same version stamp as a directory install",
                manifest.startsWith("---\nversion: ${McpConstants.SERVER_VERSION}\n")
            )
        }
    }
}
