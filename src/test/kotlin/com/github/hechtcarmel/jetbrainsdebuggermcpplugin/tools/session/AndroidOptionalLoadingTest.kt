package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.session

import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.ToolRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.net.URI
import java.net.URLClassLoader

class AndroidOptionalLoadingTest {
    @Test
    fun `tool registration does not require Android API classes`() {
        val pluginPackage = "com.github.hechtcarmel.jetbrainsdebuggermcpplugin."
        val registryClass = ToolRegistry::class.java
        val classEntry = registryClass.name.replace('.', '/') + ".class"
        val resource = requireNotNull(registryClass.getResource("/${classEntry}"))
        val rootUrl = if (resource.protocol == "jar") {
            resource.toExternalForm().removePrefix("jar:").substringBefore("!/")
        } else {
            resource.toExternalForm().removeSuffix(classEntry)
        }
        val location = URI.create(rootUrl).toURL()
        // Load plugin classes separately so eager Android references cannot use the SDK-enabled
        // test loader. IntelliJ and the plugin's other dependencies remain available through it.
        val isolated = object : URLClassLoader(arrayOf(location), registryClass.classLoader) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> {
                if (name.startsWith("com.android.") || name.startsWith("org.jetbrains.android.")) {
                    throw ClassNotFoundException(name)
                }
                if (!name.startsWith(pluginPackage)) return super.loadClass(name, resolve)
                synchronized(getClassLoadingLock(name)) {
                    val loaded = findLoadedClass(name) ?: findClass(name)
                    if (resolve) resolveClass(loaded)
                    return loaded
                }
            }
        }
        isolated.use { loader ->
            assertThrows(ClassNotFoundException::class.java) { loader.loadClass("com.android.ddmlib.Client") }
            val type = loader.loadClass(registryClass.name)
            val registry = type.getConstructor().newInstance()
            type.getMethod("registerBuiltInTools").invoke(registry)
            assertEquals(30, type.getMethod("getToolCount").invoke(registry))
        }
    }
}
