package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.util

import org.junit.Assert.*
import org.junit.Test

class StackFrameUtilsTest {

    @Test
    fun `isLibraryPath returns true for jar files`() {
        assertTrue(StackFrameUtils.isLibraryPath("/home/user/.m2/repository/org/example/lib.jar!/com/example/Class.class"))
        assertTrue(StackFrameUtils.isLibraryPath("C:/Users/user/.gradle/caches/modules/lib.jar!/org/foo/Bar.class"))
    }

    @Test
    fun `isLibraryPath returns true for jdk paths`() {
        assertTrue(StackFrameUtils.isLibraryPath("/usr/lib/jvm/jdk/lib/rt.jar"))
    }

    @Test
    fun `isLibraryPath returns false for project paths`() {
        assertFalse(StackFrameUtils.isLibraryPath("/home/user/project/src/main/java/com/example/Main.java"))
        assertFalse(StackFrameUtils.isLibraryPath("/Users/dev/myapp/src/App.kt"))
        assertFalse(StackFrameUtils.isLibraryPath("C:/Projects/webapp/src/Controller.java"))
    }

    @Test
    fun `isLibraryPath returns false for null path`() {
        assertFalse(StackFrameUtils.isLibraryPath(null))
    }

    @Test
    fun `isLibraryPath returns false for empty path`() {
        assertFalse(StackFrameUtils.isLibraryPath(""))
    }

    @Test
    fun `isLibraryPath is case sensitive for jar extension`() {
        assertTrue(StackFrameUtils.isLibraryPath("/path/to/lib.jar!/Class.class"))
        // Only lowercase .jar! is detected
        assertFalse(StackFrameUtils.isLibraryPath("/path/to/lib.JAR!/Class.class"))
    }

    @Test
    fun `isLibraryPath detects jdk in path`() {
        assertTrue(StackFrameUtils.isLibraryPath("/some/path/jdk/version/lib/src"))
    }
}
