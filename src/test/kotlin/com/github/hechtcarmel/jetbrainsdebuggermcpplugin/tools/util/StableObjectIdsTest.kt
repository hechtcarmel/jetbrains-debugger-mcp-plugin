package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Pins the two properties tools rely on: the same object always maps to the same ID (set/list/
 * remove round-trips, wait_for_pause breakpoint filtering), and distinct objects never share one —
 * including when their `hashCode()`s collide, which is exactly the failure mode of the
 * `hashCode().toString()` scheme this registry replaced.
 */
class StableObjectIdsTest {

    private class CollidingHash {
        override fun hashCode(): Int = 42
    }

    @Test
    fun `the same object always gets the same id`() {
        val obj = Any()
        assertEquals(StableObjectIds.idFor(obj), StableObjectIds.idFor(obj))
    }

    @Test
    fun `distinct objects get distinct ids even when their hashCodes collide`() {
        val a = CollidingHash()
        val b = CollidingHash()
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(StableObjectIds.idFor(a), StableObjectIds.idFor(b))
    }

    @Test
    fun `ids are opaque and not derived from hashCode`() {
        val obj = Any()
        assertNotEquals(obj.hashCode().toString(), StableObjectIds.idFor(obj))
    }
}
