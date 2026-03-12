package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.server

import org.junit.Assert.*
import org.junit.Test

class StreamableHttpSessionManagerTest {

    @Test
    fun `createSession returns non-empty id`() {
        val manager = StreamableHttpSessionManager()
        val sessionId = manager.createSession()
        assertTrue(sessionId.isNotBlank())
    }

    @Test
    fun `createSession returns unique ids`() {
        val manager = StreamableHttpSessionManager()
        val ids = (1..100).map { manager.createSession() }.toSet()
        assertEquals(100, ids.size)
    }

    @Test
    fun `getSession returns null for unknown id`() {
        val manager = StreamableHttpSessionManager()
        assertNull(manager.getSession("nonexistent"))
    }

    @Test
    fun `getSession returns session after create`() {
        val manager = StreamableHttpSessionManager()
        val sessionId = manager.createSession()
        val session = manager.getSession(sessionId)
        assertNotNull(session)
        assertEquals(sessionId, session!!.sessionId)
    }

    @Test
    fun `removeSession makes it unavailable`() {
        val manager = StreamableHttpSessionManager()
        val sessionId = manager.createSession()
        manager.removeSession(sessionId)
        assertNull(manager.getSession(sessionId))
    }

    @Test
    fun `removeSession for nonexistent does not throw`() {
        val manager = StreamableHttpSessionManager()
        manager.removeSession("nonexistent")
    }

    @Test
    fun `getActiveSessionCount tracks sessions`() {
        val manager = StreamableHttpSessionManager()
        assertEquals(0, manager.getActiveSessionCount())
        val id1 = manager.createSession()
        assertEquals(1, manager.getActiveSessionCount())
        manager.createSession()
        assertEquals(2, manager.getActiveSessionCount())
        manager.removeSession(id1)
        assertEquals(1, manager.getActiveSessionCount())
    }

    @Test
    fun `closeAllSessions removes all`() {
        val manager = StreamableHttpSessionManager()
        manager.createSession()
        manager.createSession()
        manager.createSession()
        assertEquals(3, manager.getActiveSessionCount())
        manager.closeAllSessions()
        assertEquals(0, manager.getActiveSessionCount())
    }

    @Test
    fun `session id contains only hex characters without dashes`() {
        val manager = StreamableHttpSessionManager()
        val sessionId = manager.createSession()
        assertFalse(sessionId.contains("-"))
        assertTrue(sessionId.all { it in '0'..'9' || it in 'a'..'f' })
    }
}
