package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.session

import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.ToolExecutionError
import com.intellij.openapi.project.Project
import com.intellij.xdebugger.XDebugSession
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class AndroidProcessAttachTest {
    private val main = AndroidProcessInfo("emulator-5554", 123, "com.example.app", false)
    private val service = AndroidProcessInfo("emulator-5554", 124, "com.example.app:service", false)
    private val other = main.copy(deviceSerial = "emulator-5556")
    private val project = proxy(Project::class.java)
    private object Handle : AndroidProcessHandle

    private fun arguments(json: String) = Json.parseToJsonElement(json).jsonObject
    private fun selector(json: String) = AndroidProcessSelector.from(arguments(json))

    @Test
    fun `exact name distinguishes the main process from a subprocess`() {
        assertEquals(main, selector("""{"process_name":"com.example.app"}""").select(listOf(service, main)))
        assertEquals(service, selector("""{"process_name":"com.example.app:service"}""").select(listOf(main, service)))
    }

    @Test
    fun `PID and device select an exact target across devices`() {
        assertEquals(other, selector("""{"process_id":123,"device_serial":"emulator-5556"}""").select(listOf(main, other)))
    }

    @Test
    fun `PID alone selects the only matching process`() {
        assertEquals(service, selector("""{"process_id":124}""").select(listOf(main, service)))
    }

    @Test
    fun `name and PID must both match`() {
        val error = assertThrows(ToolExecutionError::class.java) {
            selector("""{"process_id":124,"process_name":"com.example.app"}""").select(listOf(main, service))
        }
        assertTrue(error.message.startsWith("No matching Android process."))
        assertTrue(error.message.contains("com.example.app:service"))
    }

    @Test
    fun `duplicate names or PIDs never choose the first device`() {
        for (query in listOf("""{"process_id":123}""", """{"process_name":"com.example.app"}""")) {
            val error = assertThrows(ToolExecutionError::class.java) { selector(query).select(listOf(main, other)) }
            assertTrue(error.message.startsWith("Multiple matching Android processes."))
            assertTrue(error.message.contains("emulator-5554"))
            assertTrue(error.message.contains("emulator-5556"))
        }
    }

    @Test
    fun `a disappeared process returns an actionable error`() {
        val error = assertThrows(ToolExecutionError::class.java) { selector("""{"process_id":123}""").select(emptyList()) }
        assertEquals("No matching Android process. Available processes: []", error.message)
    }

    @Test
    fun `unknown names do not match processes whose name is not yet available`() {
        assertThrows(ToolExecutionError::class.java) {
            selector("""{"process_name":"null"}""").select(listOf(main.copy(processName = null)))
        }
        assertEquals(main.copy(processName = null), selector("""{"process_id":123}""").select(listOf(main.copy(processName = null))))
    }

    @Test
    fun `invalid selectors are rejected before accessing Android`() {
        for (query in listOf("""{"process_id":"123"}""", """{"process_id":0}""", """{"process_name":" "}""",
            """{"process_id":123,"device_serial":""}""", """{"device_serial":"emulator-5554"}""")) {
            assertThrows(ToolExecutionError::class.java) { AndroidProcessSelector.from(arguments(query)) }
        }
        assertThrows(ToolExecutionError::class.java) { AndroidProcessSelector.from(arguments("""{"configuration_name":"Application"}""")) }
    }

    private class Backend(private val candidates: List<AndroidProcessCandidate>) : AndroidAttachBackend {
        var calls = 0
        override suspend fun processes(project: Project) = candidates.also { calls++ }
        override suspend fun attach(project: Project, process: AndroidProcessCandidate, debuggerId: String): XDebugSession =
            error("Unexpected attachment")
    }

    @Test
    fun `process discovery returns structured content and honors the device filter`() = runBlocking {
        val backend = Backend(listOf(AndroidProcessCandidate(main, Handle), AndroidProcessCandidate(other, Handle)))
        val result = ListAndroidProcessesTool(backend).execute(project, arguments("""{"device_serial":"emulator-5556"}"""))
        assertEquals(false, result.isError)
        val processes = result.structuredContent!!["processes"]!!.jsonArray
        assertEquals(1, processes.size)
        assertEquals("emulator-5556", processes[0].jsonObject["deviceSerial"]!!.jsonPrimitive.content)
        assertEquals(1, backend.calls)
    }

    @Test
    fun `launch and attach parameters cannot be mixed`() = runBlocking {
        val backend = Backend(emptyList())
        val result = AttachAndroidDebuggerTool(backend).execute(project, arguments("""{"configuration_name":"Application","process_id":123}"""))
        assertEquals(true, result.isError)
        assertEquals("attach_android_debugger does not accept configuration_name. Use start_debug_session to launch a configuration.", (result.content.single() as TextContent).text)
        assertEquals(0, backend.calls)
    }

    @Test
    fun `ambiguous attachment returns an MCP error without invoking attach`() = runBlocking {
        val backend = Backend(listOf(AndroidProcessCandidate(main, Handle), AndroidProcessCandidate(other, Handle)))
        val result = AttachAndroidDebuggerTool(backend).execute(project, arguments("""{"process_id":123}"""))
        assertEquals(true, result.isError)
        assertTrue((result.content.single() as TextContent).text.contains("Multiple matching Android processes"))
    }

    @Test
    fun `missing configuration keeps the existing error contract`() = runBlocking {
        val result = StartDebugSessionTool().execute(project, arguments("{}"))
        assertEquals(true, result.isError)
        assertEquals("Missing required parameter: configuration_name", (result.content.single() as TextContent).text)
    }

    @Test
    fun `Android attachment requires a process selector before accessing the backend`() = runBlocking {
        val backend = Backend(emptyList())
        val result = AttachAndroidDebuggerTool(backend).execute(project, arguments("{}"))
        assertEquals(true, result.isError)
        assertEquals("Android attachment requires process_id or process_name", (result.content.single() as TextContent).text)
        assertEquals(0, backend.calls)
    }

    @Test
    fun `bridge timeout is bounded and does not cancel shared IDE initialization`() {
        val future = object : Future<Any> {
            override fun get(timeout: Long, unit: TimeUnit): Any {
                assertEquals(10L, timeout)
                assertEquals(TimeUnit.SECONDS, unit)
                throw TimeoutException()
            }
            override fun get(): Any = error("Unbounded wait")
            override fun cancel(mayInterruptIfRunning: Boolean): Boolean = error("Shared bridge must not be cancelled")
            override fun isCancelled() = false
            override fun isDone() = false
        }
        val failure = assertThrows(ToolExecutionError::class.java) { runBlocking { awaitBridge(future) } }
        assertTrue(failure.message.contains("SDK/ADB configuration"))
    }

    @Test
    fun `bridge completion preserves the initialized bridge or underlying failure`() = runBlocking {
        val bridge = Any()
        assertSame(bridge, awaitBridge(CompletableFuture.completedFuture(bridge)))
        val failure = IllegalStateException("ADB path unavailable")
        val failed = CompletableFuture<Any>().also { it.completeExceptionally(failure) }
        assertSame(failure, assertThrows(IllegalStateException::class.java) { runBlocking { awaitBridge(failed) } })
    }

    @Test
    fun `cancellation remains cancellation across Android tool boundaries`() {
        val backend = object : AndroidAttachBackend {
            override suspend fun processes(project: Project): List<AndroidProcessCandidate> = throw CancellationException("cancelled")
            override suspend fun attach(project: Project, process: AndroidProcessCandidate, debuggerId: String): XDebugSession =
                error("Unexpected attachment")
        }
        for (tool in listOf(ListAndroidProcessesTool(backend), AttachAndroidDebuggerTool(backend))) {
            assertThrows(CancellationException::class.java) {
                runBlocking { tool.execute(project, arguments("""{"process_id":123}""")) }
            }
        }
    }

    @Test
    fun `linkage failures remain confined to Android tool calls`() = runBlocking {
        for (failure in listOf(NoClassDefFoundError("missing SDK"), NoSuchMethodError("changed API"),
            ExceptionInInitializerError("Android initialization failed"))) {
            val backend = object : AndroidAttachBackend {
                override suspend fun processes(project: Project): List<AndroidProcessCandidate> = throw failure
                override suspend fun attach(project: Project, process: AndroidProcessCandidate, debuggerId: String): XDebugSession =
                    error("Unexpected attachment")
            }
            val result = ListAndroidProcessesTool(backend).execute(project, arguments("{}"))
            assertEquals(true, result.isError)
            val message = (result.content.single() as TextContent).text
            assertTrue(message.contains(failure.javaClass.simpleName))
            assertTrue(message.contains("Attach Debugger to Android Process"))
        }
    }

    private fun <T> proxy(type: Class<T>): T = type.cast(Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { receiver, method, arguments ->
        when (method.name) {
            "toString" -> type.simpleName
            "hashCode" -> 1
            "equals" -> receiver === arguments?.singleOrNull()
            else -> throw UnsupportedOperationException(method.name)
        }
    })
}
