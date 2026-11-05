package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.session

import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.McpConstants
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.server.transport.McpHttpTestCase
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.ExtensionTestUtil
import com.intellij.xdebugger.XDebugSession
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class AndroidToolsBehaviorTest : McpHttpTestCase() {
    fun `test Android descriptor registers the typed backend`() {
        assertTrue(AndroidAttachBackend.EP_NAME.extensionList.single() is TypedAndroidAttachBackend)
    }

    fun `test optional backend is discovered on invocation`() {
        val headers = sessionHeaders(initializeStreamable())
        val path = McpConstants.STREAMABLE_HTTP_ENDPOINT_PATH
        val backend = object : AndroidAttachBackend {
            override suspend fun processes(project: Project) = listOf(
                AndroidProcessCandidate(AndroidProcessInfo("emulator-5554", 123, "com.example.app", false), object : AndroidProcessHandle {})
            )
            override suspend fun attach(project: Project, process: AndroidProcessCandidate, debuggerId: String): XDebugSession =
                error("Unexpected attachment")
        }
        val disposable = Disposer.newDisposable()
        try {
            ExtensionTestUtil.maskExtensions(AndroidAttachBackend.EP_NAME, listOf(backend), disposable)
            val result = pumpingEdt { post(path, toolCall("list_android_processes"), headers) }
                .jsonBody()["result"]!!.jsonObject
            assertEquals(false, result["isError"]!!.jsonPrimitive.booleanOrNull)
            assertEquals("com.example.app", result["structuredContent"]!!.jsonObject["processes"]!!.jsonArray[0]
                .jsonObject["processName"]!!.jsonPrimitive.content)
        } finally {
            Disposer.dispose(disposable)
        }
    }

    fun `test unavailable Android backend leaves tool discovery and other tools operational`() {
        ExtensionTestUtil.maskExtensions(AndroidAttachBackend.EP_NAME, emptyList(), testRootDisposable)
        val headers = sessionHeaders(initializeStreamable())
        val path = McpConstants.STREAMABLE_HTTP_ENDPOINT_PATH

        val listed = post(path, rpc("tools/list"), headers).jsonBody()["result"]!!.jsonObject["tools"]!!.jsonArray
        val names = listed.map { it.jsonObject["name"]!!.jsonPrimitive.content }.toSet()
        assertTrue(names.containsAll(listOf("list_android_processes", "attach_android_debugger", "get_console_output")))

        for (name in listOf("list_android_processes", "attach_android_debugger")) {
            val arguments = if (name == "attach_android_debugger") """{"process_id":123}""" else "{}"
            val response = pumpingEdt { post(path, toolCall(name, arguments), headers) }
            assertEquals(200, response.statusCode())
            val body = response.jsonBody()
            assertFalse(body.containsKey("error"))
            val result = body["result"]!!.jsonObject
            assertEquals(true, result["isError"]!!.jsonPrimitive.booleanOrNull)
            assertTrue(result["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content.contains("requires the Android plugin"))
        }

        val response = pumpingEdt { post(path, toolCall("list_breakpoints"), headers) }
        assertEquals(200, response.statusCode())
        assertEquals(false, response.jsonBody()["result"]!!.jsonObject["isError"]!!.jsonPrimitive.booleanOrNull)
    }
}
