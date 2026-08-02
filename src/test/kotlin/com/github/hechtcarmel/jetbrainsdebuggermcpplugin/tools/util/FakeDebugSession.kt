package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.util

import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.execution.ui.RunnerLayoutUi
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.MessageType
import com.intellij.xdebugger.XDebugProcess
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebugSessionListener
import com.intellij.xdebugger.XSourcePosition
import com.intellij.xdebugger.breakpoints.XBreakpoint
import com.intellij.xdebugger.breakpoints.XLineBreakpoint
import com.intellij.xdebugger.frame.XExecutionStack
import com.intellij.xdebugger.frame.XStackFrame
import com.intellij.xdebugger.frame.XSuspendContext
import com.intellij.xdebugger.stepping.XSmartStepIntoHandler
import com.intellij.xdebugger.stepping.XSmartStepIntoVariant
import javax.swing.Icon
import javax.swing.event.HyperlinkListener

/**
 * A minimal hand-rolled XDebugSession for unit-testing utilities that only read session state.
 * Configure the `fake*` fields; everything a real debug process would need throws.
 */
class FakeDebugSession : XDebugSession {

    var fakeProject: Project? = null
    var fakePaused: Boolean = true
    var fakeStopped: Boolean = false
    var fakeMuted: Boolean = false
    var fakeSuspendContext: XSuspendContext? = null
    var fakeCurrentStackFrame: XStackFrame? = null
    var fakeTopFramePosition: XSourcePosition? = null
    var fakeSessionName: String = "fake-session"

    override fun getProject(): Project = fakeProject ?: unsupported()
    override fun isPaused(): Boolean = fakePaused
    override fun isStopped(): Boolean = fakeStopped
    override fun isSuspended(): Boolean = fakePaused
    override fun areBreakpointsMuted(): Boolean = fakeMuted
    override fun getSuspendContext(): XSuspendContext? = fakeSuspendContext
    override fun getCurrentStackFrame(): XStackFrame? = fakeCurrentStackFrame
    override fun getTopFramePosition(): XSourcePosition? = fakeTopFramePosition
    override fun getCurrentPosition(): XSourcePosition? = fakeCurrentStackFrame?.sourcePosition
    override fun getSessionName(): String = fakeSessionName

    override fun getDebugProcess(): XDebugProcess = unsupported()
    override fun stepOver(ignoreBreakpoints: Boolean): Unit = unsupported()
    override fun stepInto(): Unit = unsupported()
    override fun stepOut(): Unit = unsupported()
    override fun forceStepInto(): Unit = unsupported()
    override fun runToPosition(position: XSourcePosition, ignoreBreakpoints: Boolean): Unit = unsupported()
    override fun pause(): Unit = unsupported()
    override fun resume(): Unit = unsupported()
    override fun showExecutionPoint(): Unit = unsupported()
    override fun setCurrentStackFrame(executionStack: XExecutionStack, frame: XStackFrame, isTopFrame: Boolean): Unit =
        unsupported()

    override fun updateBreakpointPresentation(
        breakpoint: XLineBreakpoint<*>,
        icon: Icon?,
        errorMessage: String?
    ): Unit = unsupported()

    override fun setBreakpointVerified(breakpoint: XLineBreakpoint<*>): Unit = unsupported()
    override fun setBreakpointInvalid(breakpoint: XLineBreakpoint<*>, errorMessage: String?): Unit = unsupported()
    override fun breakpointReached(
        breakpoint: XBreakpoint<*>,
        evaluatedLogExpression: String?,
        suspendContext: XSuspendContext
    ): Boolean = unsupported()

    override fun positionReached(suspendContext: XSuspendContext): Unit = unsupported()
    override fun sessionResumed(): Unit = unsupported()
    override fun stop(): Unit = unsupported()
    override fun setBreakpointMuted(muted: Boolean) {
        fakeMuted = muted
    }

    override fun addSessionListener(listener: XDebugSessionListener, parentDisposable: Disposable) {}
    override fun addSessionListener(listener: XDebugSessionListener) {}
    override fun removeSessionListener(listener: XDebugSessionListener) {}
    override fun reportMessage(message: String, type: MessageType, listener: HyperlinkListener?): Unit = unsupported()
    override fun getRunContentDescriptor(): RunContentDescriptor = unsupported()
    override fun getRunProfile(): RunProfile? = null
    override fun setPauseActionSupported(isSupported: Boolean) {}
    override fun rebuildViews() {}
    override fun <V : XSmartStepIntoVariant> smartStepInto(handler: XSmartStepIntoHandler<V>, variant: V): Unit =
        unsupported()

    override fun updateExecutionPosition() {}
    override fun initBreakpoints() {}
    override fun getConsoleView(): ConsoleView = unsupported()
    override fun getUI(): RunnerLayoutUi = unsupported()
    override fun isMixedMode(): Boolean = false
    override fun getExecutionEnvironment(): ExecutionEnvironment? = null

    private fun unsupported(): Nothing =
        throw UnsupportedOperationException("FakeDebugSession does not support this operation")
}

/**
 * A suspend context whose active stack is [activeStack] and whose full thread list is
 * [activeStack] plus [others], delivered synchronously in a single last=true batch.
 */
fun suspendContextOf(activeStack: XExecutionStack, vararg others: XExecutionStack): XSuspendContext =
    object : XSuspendContext() {
        override fun getActiveExecutionStack(): XExecutionStack = activeStack
        override fun computeExecutionStacks(container: XExecutionStackContainer) {
            container.addExecutionStack(listOf(activeStack) + others, true)
        }
    }

