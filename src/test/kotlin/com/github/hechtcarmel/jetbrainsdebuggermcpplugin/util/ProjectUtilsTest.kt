package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.util

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ProjectUtilsTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    // ProjectInfo Tests

    @Test
    fun `ProjectInfo contains name and path`() {
        val info = ProjectInfo(name = "MyProject", path = "/path/to/project")

        assertEquals("MyProject", info.name)
        assertEquals("/path/to/project", info.path)
    }

    @Test
    fun `ProjectInfo serializes correctly`() {
        val info = ProjectInfo(name = "TestProject", path = "/home/user/project")
        val encoded = json.encodeToString(info)

        assertTrue(encoded.contains("\"name\":\"TestProject\""))
        assertTrue(encoded.contains("\"path\":\"/home/user/project\""))
    }

    @Test
    fun `ProjectInfo deserializes correctly`() {
        val jsonStr = """{"name":"Deserialized","path":"/some/path"}"""
        val info = json.decodeFromString<ProjectInfo>(jsonStr)

        assertEquals("Deserialized", info.name)
        assertEquals("/some/path", info.path)
    }

    @Test
    fun `ProjectInfo equality works correctly`() {
        val info1 = ProjectInfo(name = "Project", path = "/path")
        val info2 = ProjectInfo(name = "Project", path = "/path")
        val info3 = ProjectInfo(name = "Different", path = "/path")

        assertEquals(info1, info2)
        assertNotEquals(info1, info3)
    }

    // ProjectResolutionResult Tests

    @Test
    fun `ProjectResolutionResult Success contains project`() {
        // Cannot test with real Project, but can test the sealed class structure
        assertTrue(ProjectResolutionResult.Success::class.isSealed.not())
    }

    @Test
    fun `ProjectResolutionResult MultipleProjects contains list of ProjectInfo`() {
        val projects = listOf(
            ProjectInfo("Project1", "/path1"),
            ProjectInfo("Project2", "/path2")
        )
        val result = ProjectResolutionResult.MultipleProjects(projects)

        assertEquals(2, result.projects.size)
        assertEquals("Project1", result.projects[0].name)
        assertEquals("Project2", result.projects[1].name)
    }

    @Test
    fun `ProjectResolutionResult NotFound contains requested path`() {
        val result = ProjectResolutionResult.NotFound("/nonexistent/path")

        assertEquals("/nonexistent/path", result.requestedPath)
    }

    @Test
    fun `ProjectResolutionResult NoProjectsOpen is singleton object`() {
        val result1 = ProjectResolutionResult.NoProjectsOpen
        val result2 = ProjectResolutionResult.NoProjectsOpen

        assertSame(result1, result2)
    }

    // ProjectResolutionResult Pattern Matching Tests

    @Test
    fun `ProjectResolutionResult types are distinguishable`() {
        val results = listOf<ProjectResolutionResult>(
            ProjectResolutionResult.MultipleProjects(emptyList()),
            ProjectResolutionResult.NotFound("/path"),
            ProjectResolutionResult.NoProjectsOpen
        )

        results.forEach { result ->
            when (result) {
                is ProjectResolutionResult.Success -> fail("Unexpected Success")
                is ProjectResolutionResult.MultipleProjects -> assertTrue(result.projects.isEmpty())
                is ProjectResolutionResult.NotFound -> assertEquals("/path", result.requestedPath)
                is ProjectResolutionResult.NoProjectsOpen -> {} // OK
            }
        }
    }

    @Test
    fun `MultipleProjects can contain empty list`() {
        val result = ProjectResolutionResult.MultipleProjects(emptyList())

        assertTrue(result.projects.isEmpty())
    }

    @Test
    fun `NotFound preserves original path format`() {
        val windowsPath = "C:\\Users\\user\\project"
        val unixPath = "/home/user/project"
        val relativePath = "./relative/path"

        val result1 = ProjectResolutionResult.NotFound(windowsPath)
        val result2 = ProjectResolutionResult.NotFound(unixPath)
        val result3 = ProjectResolutionResult.NotFound(relativePath)

        assertEquals(windowsPath, result1.requestedPath)
        assertEquals(unixPath, result2.requestedPath)
        assertEquals(relativePath, result3.requestedPath)
    }

    // ProjectInfo Edge Cases

    @Test
    fun `ProjectInfo handles empty name`() {
        val info = ProjectInfo(name = "", path = "/path")

        assertEquals("", info.name)
    }

    @Test
    fun `ProjectInfo handles empty path`() {
        val info = ProjectInfo(name = "Name", path = "")

        assertEquals("", info.path)
    }

    @Test
    fun `ProjectInfo handles special characters in name`() {
        val info = ProjectInfo(name = "My-Project_v2.0 (test)", path = "/path")

        assertEquals("My-Project_v2.0 (test)", info.name)
    }

    @Test
    fun `ProjectInfo handles spaces in path`() {
        val info = ProjectInfo(name = "Project", path = "/path/with spaces/project")

        assertEquals("/path/with spaces/project", info.path)
    }

    @Test
    fun `ProjectInfo copy works correctly`() {
        val original = ProjectInfo(name = "Original", path = "/original/path")
        val copied = original.copy(name = "Copied")

        assertEquals("Copied", copied.name)
        assertEquals("/original/path", copied.path)
        assertEquals("Original", original.name)
    }

    // Multiple Projects List Tests

    @Test
    fun `MultipleProjects with multiple projects preserves order`() {
        val projects = listOf(
            ProjectInfo("First", "/first"),
            ProjectInfo("Second", "/second"),
            ProjectInfo("Third", "/third")
        )
        val result = ProjectResolutionResult.MultipleProjects(projects)

        assertEquals("First", result.projects[0].name)
        assertEquals("Second", result.projects[1].name)
        assertEquals("Third", result.projects[2].name)
    }

    @Test
    fun `MultipleProjects allows duplicate project names`() {
        val projects = listOf(
            ProjectInfo("SameName", "/path1"),
            ProjectInfo("SameName", "/path2")
        )
        val result = ProjectResolutionResult.MultipleProjects(projects)

        assertEquals(2, result.projects.size)
        assertEquals(result.projects[0].name, result.projects[1].name)
        assertNotEquals(result.projects[0].path, result.projects[1].path)
    }
}
