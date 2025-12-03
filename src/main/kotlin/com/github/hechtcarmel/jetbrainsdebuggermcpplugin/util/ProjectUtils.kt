package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.util

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import kotlinx.serialization.Serializable
import java.io.File

@Serializable
data class ProjectInfo(
    val name: String,
    val path: String
)

sealed class ProjectResolutionResult {
    data class Success(val project: Project) : ProjectResolutionResult()
    data class MultipleProjects(val projects: List<ProjectInfo>) : ProjectResolutionResult()
    data class NotFound(val requestedPath: String) : ProjectResolutionResult()
    data object NoProjectsOpen : ProjectResolutionResult()
}

object ProjectUtils {

    fun resolveProject(projectPath: String?): ProjectResolutionResult {
        val openProjects = getOpenProjects()

        if (openProjects.isEmpty()) {
            return ProjectResolutionResult.NoProjectsOpen
        }

        if (projectPath != null) {
            val normalizedPath = File(projectPath).canonicalPath
            val matchingProject = openProjects.find { project ->
                val basePath = project.basePath ?: return@find false
                File(basePath).canonicalPath == normalizedPath
            }

            return if (matchingProject != null) {
                ProjectResolutionResult.Success(matchingProject)
            } else {
                ProjectResolutionResult.NotFound(projectPath)
            }
        }

        return if (openProjects.size == 1) {
            ProjectResolutionResult.Success(openProjects.first())
        } else {
            ProjectResolutionResult.MultipleProjects(
                openProjects.map { project ->
                    ProjectInfo(
                        name = project.name,
                        path = project.basePath ?: ""
                    )
                }
            )
        }
    }

    fun getOpenProjects(): List<Project> {
        return ProjectManager.getInstance().openProjects
            .filter { !it.isDefault && it.isInitialized }
    }

    fun getOpenProjectInfos(): List<ProjectInfo> {
        return getOpenProjects().map { project ->
            ProjectInfo(
                name = project.name,
                path = project.basePath ?: ""
            )
        }
    }
}
