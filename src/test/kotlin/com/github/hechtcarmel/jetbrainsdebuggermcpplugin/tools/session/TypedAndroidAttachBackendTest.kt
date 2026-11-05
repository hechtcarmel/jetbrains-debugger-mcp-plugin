package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.session

import com.android.ddmlib.AndroidDebugBridge
import com.android.ddmlib.Client
import com.android.ddmlib.ClientData
import com.android.ddmlib.IDevice
import com.android.tools.idea.execution.common.debug.AndroidDebugger
import com.android.tools.idea.execution.common.debug.AndroidDebuggerState
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.ToolExecutionError
import com.intellij.openapi.project.Project
import com.intellij.xdebugger.XDebugSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy

class TypedAndroidAttachBackendTest {
    private val project = proxy(Project::class.java) { name -> error("Unexpected project call: $name") }
    private val session = sessionIn(project)

    private class Bridge(private val entries: Array<IDevice>, private val ready: Boolean) : AndroidDebugBridge() {
        override fun getDevices() = entries
        override fun isConnected() = ready
        override fun hasInitialDeviceList() = ready
    }

    private class Starter(private val result: suspend () -> XDebugSession) : AndroidSessionStarter {
        var calls = 0
        var receivedClient: Client? = null
        var receivedDebugger: AndroidDebugger<*>? = null
        var receivedState: AndroidDebuggerState? = null
        override suspend fun <S : AndroidDebuggerState> attach(
            project: Project, client: Client, debugger: AndroidDebugger<S>, state: S
        ): XDebugSession {
            calls++
            receivedClient = client
            receivedDebugger = debugger
            receivedState = state
            return result()
        }
    }

    private fun backend(
        devices: Array<IDevice>, debuggers: List<AndroidDebugger<*>> = listOf(debugger()),
        starter: Starter = Starter { session }, ready: Boolean = true
    ) = TypedAndroidAttachBackend({ Bridge(devices, ready) }, { debuggers }, starter)

    private fun client(pid: Int, name: String?, valid: Boolean = true, attached: Boolean = false): Client {
        lateinit var data: ClientData
        val client = proxy(Client::class.java) { method ->
            when (method) {
                "getClientData" -> data
                "isValid" -> valid
                "isDebuggerAttached" -> attached
                else -> error("Unexpected client call: $method")
            }
        }
        data = object : ClientData(client, pid) {
            override fun getProcessName() = name
        }
        return client
    }

    private fun device(serial: String, clients: Array<Client>, online: Boolean = true): IDevice =
        proxy(IDevice::class.java) { method ->
            when (method) {
                "getSerialNumber" -> serial
                "getClients" -> clients
                "isOnline" -> online
                else -> error("Unexpected device call: $method")
            }
        }

    @Suppress("UNCHECKED_CAST")
    private fun debugger(
        existing: XDebugSession? = null, supported: Boolean = true,
        state: AndroidDebuggerState = AndroidDebuggerState(), allowStateCreation: Boolean = true
    ): AndroidDebugger<AndroidDebuggerState> = proxy(AndroidDebugger::class.java) { method ->
        when (method) {
            "getId" -> "Java"
            "supportsProject" -> supported
            "getExistingDebugSession" -> existing
            "createState" -> if (allowStateCreation) state else error("State creation must not occur")
            else -> error("Unexpected debugger call: $method")
        }
    } as AndroidDebugger<AndroidDebuggerState>

    @Test
    fun `typed discovery preserves metadata and filters invalid clients and offline devices`() = runBlocking {
        val backend = backend(arrayOf(
            device("emulator-5556", arrayOf(client(123, "com.example.app:service", attached = true))),
            device("emulator-5554", arrayOf(client(5, null), client(6, "gone", valid = false))),
            device("offline", arrayOf(client(7, "offline")), online = false)
        ))
        assertEquals(listOf(
            AndroidProcessInfo("emulator-5554", 5, null, false),
            AndroidProcessInfo("emulator-5556", 123, "com.example.app:service", true)
        ), backend.processes(project).map { it.info })
    }

    @Test
    fun `empty devices succeed and an incomplete bridge returns a retryable error`() = runBlocking {
        assertTrue(backend(emptyArray()).processes(project).isEmpty())
        val failure = assertThrows(ToolExecutionError::class.java) {
            runBlocking { backend(emptyArray(), ready = false).processes(project) }
        }
        assertTrue(failure.message.contains("not ready"))
    }

    @Test
    fun `typed attachment passes the selected client and matching debugger state`() = runBlocking {
        val client = client(123, "com.example.app")
        val state = AndroidDebuggerState()
        val debugger = debugger(state = state)
        val starter = Starter { session }
        val backend = backend(arrayOf(device("emulator-5554", arrayOf(client))), listOf(debugger), starter)
        assertSame(session, backend.attach(project, backend.processes(project).single(), "Java"))
        assertSame(client, starter.receivedClient)
        assertSame(debugger, starter.receivedDebugger)
        assertSame(state, starter.receivedState)
    }

    @Test
    fun `existing sessions are reused without creating state or starting a session`() = runBlocking {
        val starter = Starter { error("Unexpected session start") }
        val backend = backend(arrayOf(device("emulator-5554", arrayOf(client(123, "app", attached = true)))),
            listOf(debugger(existing = session, allowStateCreation = false)), starter)
        assertSame(session, backend.attach(project, backend.processes(project).single(), "Java"))
        assertEquals(0, starter.calls)
    }

    @Test
    fun `sessions in another project and external debuggers are refused`() {
        for (existing in listOf(sessionIn(proxy(Project::class.java) { error(it) }), null)) {
            val starter = Starter { error("Unexpected session start") }
            val backend = backend(arrayOf(device("emulator-5554", arrayOf(client(123, "app", attached = true)))),
                listOf(debugger(existing)), starter)
            val failure = assertThrows(ToolExecutionError::class.java) {
                runBlocking { backend.attach(project, backend.processes(project).single(), "Java") }
            }
            assertTrue(failure.message.contains(if (existing == null) "already has a debugger" else "another project"))
            assertEquals(0, starter.calls)
        }
    }

    @Test
    fun `debugger IDs and project support are checked before attachment`() {
        for ((id, supported) in listOf("missing" to true, "Java" to false)) {
            val starter = Starter { error("Unexpected session start") }
            val backend = backend(arrayOf(device("emulator-5554", arrayOf(client(123, "app")))),
                listOf(debugger(supported = supported)), starter)
            val failure = assertThrows(ToolExecutionError::class.java) {
                runBlocking { backend.attach(project, backend.processes(project).single(), id) }
            }
            assertTrue(failure.message.contains("Android debugger not available: $id"))
            assertEquals(0, starter.calls)
        }
    }

    @Test
    fun `suspended attachment returns the exact session after completion`() = runBlocking {
        val completion = CompletableDeferred<XDebugSession>()
        val starter = Starter { completion.await() }
        val backend = backend(arrayOf(device("emulator-5554", arrayOf(client(123, "app")))), starter = starter)
        val target = backend.processes(project).single()
        val attached = async { backend.attach(project, target, "Java") }
        yield()
        assertFalse(attached.isCompleted)
        assertEquals(1, starter.calls)
        completion.complete(session)
        assertSame(session, attached.await())
    }

    @Test
    fun `attachment failure and cancellation propagate through the typed backend`() {
        for (failure in listOf(IllegalStateException("Process exited"), CancellationException("cancelled"))) {
            val backend = backend(arrayOf(device("emulator-5554", arrayOf(client(123, "app")))),
                starter = Starter { throw failure })
            val caught = assertThrows(failure.javaClass) {
                runBlocking { withAndroidBackend(backend) { it.attach(project, it.processes(project).single(), "Java") } }
            }
            assertEquals(failure.message, caught.message)
        }
    }

    @Test
    fun `legacy process name access uses a typed fallback`() {
        val client = client(123, "app")
        val data = object : ClientData(client, 123) {
            override fun getProcessName(): String? = throw NoSuchMethodError("getProcessName")
            @Suppress("DEPRECATION")
            override fun getClientDescription() = "com.example.app:service"
        }
        assertEquals("com.example.app:service", processName(data))
    }

    @Test
    fun `foreign process handles are rejected`() {
        val backend = backend(emptyArray())
        val target = AndroidProcessCandidate(AndroidProcessInfo("emulator-5554", 123, "app", false), object : AndroidProcessHandle {})
        assertThrows(ToolExecutionError::class.java) { runBlocking { backend.attach(project, target, "Java") } }
    }

    private fun sessionIn(owner: Project): XDebugSession = proxy(XDebugSession::class.java) { method ->
        when (method) {
            "isStopped" -> false
            "getProject" -> owner
            else -> error("Unexpected session call: $method")
        }
    }

    private fun <T> proxy(type: Class<T>, method: (String) -> Any?): T = type.cast(
        Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { receiver, invoked, args ->
            when (invoked.name) {
                "toString" -> type.simpleName
                "hashCode" -> System.identityHashCode(receiver)
                "equals" -> receiver === args?.singleOrNull()
                else -> method(invoked.name)
            }
        }
    )
}
