package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.execution

import java.lang.reflect.Proxy

/** Only public callback contracts; no Python/DAP implementation classes or private thread IDs. */
internal object JumpRequestCallbacks {
    fun supports(type: Class<*>): Boolean = type == Function1::class.java ||
        (type.isInterface && type.methods.any {
            it.name == "ok" && it.parameterCount == 1 && it.returnType == Void.TYPE
        } && type.methods.any {
            it.name == "error" && it.parameterCount == 1 && it.returnType == Void.TYPE &&
                Throwable::class.java.isAssignableFrom(it.parameterTypes[0])
        })

    fun create(
        type: Class<*>,
        onOk: (Any?) -> Unit,
        onError: (Throwable?) -> Unit,
        onErrorMessage: (String) -> Unit,
    ): Any {
        require(supports(type)) { "Unsupported jump callback interface" }
        // The installed 263.7307.206 method's onError parameter is (String) -> Unit.
        // Use an ordinary Kotlin lambda: invoke(Object): Object returns kotlin.Unit, not null.
        // This callback is failure-only; a normal void method return is NOT acceptance.
        if (type == Function1::class.java) return onErrorMessage

        return Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { proxy, invoked, args ->
            when (invoked.name) {
                "ok" -> { onOk(args?.firstOrNull()); null }
                "error" -> { onError(args?.firstOrNull() as? Throwable); null }
                "equals" -> proxy === args?.firstOrNull()
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "jump_to_line reply callback"
                else -> null
            }
        }
    }
}
