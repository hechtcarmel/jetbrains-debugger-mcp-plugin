package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.trace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TraceExecutionToolTest {

    private fun probe(path: String, line: Int, maxHits: Int = 1) =
        TraceExecutionTool.Probe(path, line, listOf("x"), maxHits)

    @Test
    fun `a probe key is its file and line`() {
        assertEquals("/src/pool.go:61", probe("/src/pool.go", 61).key())
    }

    @Test
    fun `a probe matches the exact path and line it was given`() {
        val p = probe("/src/pool.go", 61)

        assertTrue(p.matches("/src/pool.go", 61))
        assertFalse("a different line must not match", p.matches("/src/pool.go", 62))
        assertFalse("a different file must not match", p.matches("/src/ledger.go", 61))
    }

    @Test
    fun `a probe given as a relative path matches the absolute path the debugger reports`() {
        // Callers name files the way they see them; the debugger always reports absolute paths.
        val p = probe("pool.go", 61)

        assertTrue(p.matches("/Users/someone/project/pool.go", 61))
        assertFalse(p.matches("/Users/someone/project/mempool.go", 62))
    }

    @Test
    fun `the trace is not exhausted before anything is hit`() {
        val probes = listOf(probe("a.go", 10, maxHits = 2), probe("b.go", 20))

        assertFalse(TraceExecutionTool.exhausted(emptyMap(), probes))
    }

    @Test
    fun `the trace is not exhausted while one probe still has budget`() {
        val probes = listOf(probe("a.go", 10, maxHits = 2), probe("b.go", 20))

        assertFalse(TraceExecutionTool.exhausted(mapOf("a.go:10" to 2), probes))
    }

    @Test
    fun `the trace is exhausted once every probe met its budget`() {
        val probes = listOf(probe("a.go", 10, maxHits = 2), probe("b.go", 20))

        assertTrue(TraceExecutionTool.exhausted(mapOf("a.go:10" to 2, "b.go:20" to 1), probes))
    }

    @Test
    fun `overshooting a budget still counts as exhausted`() {
        // A probe inside a hot loop can be hit again between the check and the resume; treating
        // that as "not done yet" would leave the trace waiting for a budget it can never match.
        val probes = listOf(probe("a.go", 10, maxHits = 2))

        assertTrue(TraceExecutionTool.exhausted(mapOf("a.go:10" to 5), probes))
    }
}
