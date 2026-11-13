package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.util

import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.ToolExecutionError
import com.intellij.execution.console.ConsoleViewWrapperBase
import com.intellij.execution.console.DuplexConsoleView
import com.intellij.execution.console.LanguageConsoleImpl
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.build.BuildView
import com.intellij.build.DefaultBuildDescriptor
import com.intellij.build.ViewManager
import com.intellij.openapi.actionSystem.LangDataKeys
import com.intellij.openapi.fileTypes.PlainTextLanguage
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataSink
import com.intellij.openapi.actionSystem.UiDataProvider
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class ConsoleOutputReaderTest : BasePlatformTestCase() {
    private fun page(text: String, lines: Int = 100, offset: Int = 0, maxChars: Int = 20000) =
        ConsoleOutputReader.page(EditorFactory.getInstance().createDocument(text), "session", lines, offset, maxChars)

    fun `test tail and backward pages preserve order and trailing newlines`() {
        val tail = page("one\ntwo\nthree\n", lines = 2)
        assertEquals("two\nthree\n", tail.output)
        assertEquals(3, tail.totalLines)
        assertEquals(2, tail.startLine)
        assertEquals(3, tail.endLine)
        assertTrue(tail.hasMore)
        assertFalse(tail.truncated)

        val older = page("one\ntwo\nthree\n", lines = 2, offset = 2)
        assertEquals("one\n", older.output)
        assertEquals(1, older.startLine)
        assertEquals(1, older.endLine)
        assertFalse(older.hasMore)
    }

    fun `test empty output blank lines and unterminated lines have honest counts`() {
        val empty = page("")
        assertEquals(0, empty.totalLines)
        assertEquals(0, empty.startLine)
        assertEquals(0, empty.endLine)
        assertEquals("", empty.output)
        assertEquals("\n", page("\n").output)
        assertEquals(1, page("\n").totalLines)
        assertEquals(2, page("one\n\n").totalLines)
        assertEquals(2, page("one\ntwo").totalLines)
        assertEquals("two", page("one\ntwo", lines = 1).output)
    }

    fun `test offsets beyond history including max int return an empty page`() {
        for (offset in listOf(2, 3, Int.MAX_VALUE)) {
            val result = page("one\ntwo", offset = offset)
            assertEquals(2, result.totalLines)
            assertEquals("", result.output)
            assertEquals(0, result.startLine)
            assertEquals(0, result.endLine)
            assertFalse(result.hasMore)
        }
    }

    fun `test character limit keeps the end of an oversized line without breaking Unicode`() {
        val long = page("a".repeat(200000) + "tail", maxChars = 4)
        assertEquals("tail", long.output)
        assertEquals(1, long.startLine)
        assertEquals(1, long.endLine)
        assertTrue(long.truncated)
        assertTrue(long.hasMore)
        val unicode = page("prefix😀x", maxChars = 2)
        assertEquals("x", unicode.output)
        assertTrue(unicode.truncated)
        assertTrue(unicode.output.length <= 2)
    }

    fun `test character cap adjusts first line and applies to older pages too`() {
        val result = page("one\ntwo\nthree\nfour\n", lines = 3, offset = 1, maxChars = 7)
        assertEquals("\nthree\n", result.output)
        assertEquals(2, result.startLine)
        assertEquals(3, result.endLine)
        assertTrue(result.truncated)
        assertTrue(result.hasMore)
    }

    private fun console(): ConsoleView = TextConsoleBuilderFactory.getInstance().createBuilder(project).console.apply {
        Disposer.register(testRootDisposable, this)
        component // create the editor before scheduling output
    }

    private fun waitFor(message: String, ready: () -> Boolean) {
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10)
        while (!ready() && System.nanoTime() < deadline) {
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            Thread.sleep(10)
        }
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertTrue(message, ready())
    }

    private fun render(console: ConsoleView) = waitFor("Console output never rendered") { !console.hasDeferredOutput() }

    fun `test standard console contains stdout stderr and debugger generated messages`() {
        val console = console()
        console.print("stdout\n", ConsoleViewContentType.NORMAL_OUTPUT)
        console.print("stderr\n", ConsoleViewContentType.ERROR_OUTPUT)
        console.print("logpoint i=3\n", ConsoleViewContentType.SYSTEM_OUTPUT)
        render(console)
        val snapshot = ConsoleOutputReader.read(console, "session", 100, 0, 20000)
        assertEquals("stdout\nstderr\nlogpoint i=3\n", snapshot.output)
        assertFalse(snapshot.hasDeferredOutput)
        assertEquals(snapshot, ConsoleOutputReader.read(console, "session", 100, 0, 20000))
        // The reader borrows the IDE document rather than keeping a second persistent buffer.
        console.clear()
        waitFor("Cleared output must not be retained by the reader") {
            ConsoleOutputReader.read(console, "session", 100, 0, 20000).output.isEmpty()
        }
    }

    fun `test reading paused output reports pending text without unpausing or flushing it`() {
        val console = console()
        console.print("visible\n", ConsoleViewContentType.NORMAL_OUTPUT)
        render(console)
        console.setOutputPaused(true)
        console.print("pending\n", ConsoleViewContentType.NORMAL_OUTPUT)
        val result = ConsoleOutputReader.read(console, "session", 100, 0, 20000)
        assertEquals("visible\n", result.output)
        assertTrue(result.hasDeferredOutput)
        assertTrue(result.outputPaused)
        assertTrue(console.isOutputPaused)
        console.setOutputPaused(false)
        render(console)
        assertEquals("visible\npending\n", ConsoleOutputReader.read(console, "session", 100, 0, 20000).output)
    }

    fun `test language console reads history and never unsubmitted input`() {
        val console = LanguageConsoleImpl(project, "Console reader test", PlainTextLanguage.INSTANCE)
        Disposer.register(testRootDisposable, console)
        console.component
        console.setInputText("unsubmitted secret command")
        console.print("history\n", ConsoleViewContentType.NORMAL_OUTPUT)
        render(console)
        assertEquals("history\n", ConsoleOutputReader.read(console, "session", 100, 0, 20000).output)
        assertEquals("unsubmitted secret command", console.editorDocument.text)
    }

    fun `test wrappers and duplex consoles use primary history regardless of selected view`() {
        val primary = console()
        val secondary = console()
        primary.print("primary\n", ConsoleViewContentType.NORMAL_OUTPUT)
        secondary.print("secondary\n", ConsoleViewContentType.NORMAL_OUTPUT)
        render(primary)
        render(secondary)
        val duplex = DuplexConsoleView(primary, secondary)
        Disposer.register(testRootDisposable, duplex)
        duplex.enableConsole(false)
        val wrapper = ConsoleViewWrapperBase(duplex)
        assertEquals("primary\n", ConsoleOutputReader.read(wrapper, "session", 100, 0, 20000).output)
        assertFalse(duplex.isPrimaryConsoleEnabled)
    }

    fun `test unsupported console returns a useful error instead of empty success`() {
        assertUnsupported(javax.swing.JPanel())
    }

    fun `test Gradle build composite reads process console instead of selected tree console`() {
        val process = console()
        val tree = console()
        process.print("logpoint from Gradle application\n", ConsoleViewContentType.SYSTEM_OUTPUT)
        tree.print("selected build tree details\n", ConsoleViewContentType.NORMAL_OUTPUT)
        render(process)
        render(tree)
        val manager = object : ViewManager {
            override fun isConsoleEnabledByDefault() = false
            override fun isBuildContentView() = false
        }
        val build = BuildView(project, process, DefaultBuildDescriptor("test", "Gradle", project.basePath!!, 0), null, manager)
        Disposer.register(testRootDisposable, build)
        build.addView(process, "consoleView")
        build.addView(tree, "buildTree")
        build.showView("buildTree", false)
        assertEquals("logpoint from Gradle application\n", ConsoleOutputReader.read(build, "debug", 100, 0, 20000).output)
        assertTrue("Reading must not switch the selected build view", build.isViewVisible("buildTree"))
    }

    fun `test a provider cannot redirect reading to a foreign console`() {
        val foreign = console()
        val component = object : javax.swing.JPanel(), UiDataProvider {
            override fun uiDataSnapshot(sink: DataSink) { sink[LangDataKeys.CONSOLE_VIEW] = foreign }
        }
        val view = java.lang.reflect.Proxy.newProxyInstance(ConsoleView::class.java.classLoader,
            arrayOf(ConsoleView::class.java)) { _, method, _ ->
            if (method.name == "getComponent") component else throw UnsupportedOperationException(method.name)
        } as ConsoleView
        try {
            ConsoleOutputReader.read(view, "session", 100, 0, 20000)
            fail("Foreign console must be rejected")
        } catch (e: ToolExecutionError) {
            assertTrue(e.message!!.contains("foreign view"))
        }
    }

    fun `test a foreign console editor in the data context is never read`() {
        val factory = EditorFactory.getInstance()
        val editor = factory.createViewer(factory.createDocument("another session's output\n"), project, EditorKind.CONSOLE)
        Disposer.register(testRootDisposable) { factory.releaseEditor(editor) }
        val component = object : javax.swing.JPanel(), UiDataProvider {
            override fun uiDataSnapshot(sink: DataSink) {
                sink[CommonDataKeys.EDITOR] = editor
            }
        }
        assertUnsupported(component)
    }

    private fun assertUnsupported(component: javax.swing.JComponent) {
        val console = java.lang.reflect.Proxy.newProxyInstance(
            ConsoleView::class.java.classLoader, arrayOf(ConsoleView::class.java)
        ) { _, method, _ ->
            when (method.name) {
                "getComponent" -> component
                else -> throw UnsupportedOperationException(method.name)
            }
        } as ConsoleView
        try {
            ConsoleOutputReader.read(console, "session", 100, 0, 20000)
            fail("A non-text console must not look like an empty text console")
        } catch (e: ToolExecutionError) {
            assertTrue(e.message!!.startsWith("Console output is not available as text for this debugger ("))
        }
    }
}
