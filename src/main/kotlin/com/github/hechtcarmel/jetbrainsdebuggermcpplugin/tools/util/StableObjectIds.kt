package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.util

import java.util.UUID
import java.util.WeakHashMap

/**
 * Plugin-owned registry of opaque wire IDs for platform objects (debug sessions, breakpoints).
 *
 * ## Why not `hashCode()`
 *
 * IDs used to be `obj.hashCode().toString()`. `hashCode` is not unique — two live sessions or
 * breakpoints can collide, and after a collision `getSessionById`/`remove_breakpoint` silently
 * operate on whichever object the lookup finds first. A UUID minted per object is unique for the
 * registry's lifetime and carries no accidental meaning a client could start depending on.
 *
 * IDs are opaque strings on the wire: clients only ever echo back what a tool returned, so the
 * scheme behind the string is free to change (`result-shapes.txt` is unaffected).
 *
 * ## Lifetime
 *
 * Keys are held weakly: once the IDE disposes a session or removes a breakpoint and drops its
 * last strong reference, the entry vanishes with it — the registry never keeps debugger objects
 * alive. The platform's session and breakpoint classes use identity equality, which is exactly
 * the keying [WeakHashMap] provides for them. IDs therefore live as long as the object does and
 * are NOT stable across IDE restarts — same as the hash-based scheme they replace.
 */
object StableObjectIds {

    private val ids = WeakHashMap<Any, String>()

    /** Returns the stable ID for [obj], minting one on first sight. */
    @Synchronized
    fun idFor(obj: Any): String = ids.getOrPut(obj) { UUID.randomUUID().toString() }
}
