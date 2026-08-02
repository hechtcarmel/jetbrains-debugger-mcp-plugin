package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.mcp

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Decides which open project a tool call applies to.
 *
 * This is the one place in the plugin that reports failure as a *structured* JSON payload rather
 * than as prose: an agent that picked the wrong project needs the list of real ones to retry with,
 * and prose would force it to guess. The three `error` codes and the `available_projects` shape are
 * documented in CLAUDE.md and are a client contract.
 */
object McpProjectResolver {

    private const val PARAM_PROJECT_PATH = "project_path"

    private const val ERROR_NO_PROJECT_OPEN = "no_project_open"
    private const val ERROR_PROJECT_NOT_FOUND = "project_not_found"
    private const val ERROR_MULTIPLE_PROJECTS = "multiple_projects_open"
    private const val MSG_NO_PROJECT_OPEN = "No project is currently open in the IDE"
    private const val MSG_MULTIPLE_PROJECTS =
        "Multiple projects are open. Please specify the 'project_path' parameter."

    private val json = Json { encodeDefaults = true; explicitNulls = false; prettyPrint = false }

    /** Either the resolved [Project], or the JSON payload explaining why none could be chosen. */
    sealed interface Resolution {
        data class Resolved(val project: Project) : Resolution
        data class Failed(val payload: String) : Resolution
    }

    fun resolve(arguments: JsonObject): Resolution {
        val projectPath = arguments[PARAM_PROJECT_PATH]?.jsonPrimitive?.contentOrNull
        val openProjects = ProjectManager.getInstance().openProjects.filter { !it.isDefault }

        if (openProjects.isEmpty()) {
            return Resolution.Failed(
                json.encodeToString(
                    JsonObject.serializer(),
                    buildJsonObject {
                        put("error", ERROR_NO_PROJECT_OPEN)
                        put("message", MSG_NO_PROJECT_OPEN)
                    }
                )
            )
        }

        if (projectPath != null) {
            val match = openProjects.find { it.basePath == projectPath }
            return if (match != null) {
                Resolution.Resolved(match)
            } else {
                Resolution.Failed(
                    failurePayload(ERROR_PROJECT_NOT_FOUND, "Project not found: $projectPath", openProjects)
                )
            }
        }

        if (openProjects.size == 1) return Resolution.Resolved(openProjects.first())

        return Resolution.Failed(
            failurePayload(ERROR_MULTIPLE_PROJECTS, MSG_MULTIPLE_PROJECTS, openProjects)
        )
    }

    private fun failurePayload(error: String, message: String, openProjects: List<Project>): String =
        json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put("error", error)
                put("message", message)
                put("available_projects", buildJsonArray {
                    openProjects.forEach { project ->
                        add(buildJsonObject {
                            put("name", project.name)
                            put("path", project.basePath ?: "")
                        })
                    }
                })
            }
        )
}
