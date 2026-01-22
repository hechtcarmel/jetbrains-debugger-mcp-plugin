package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.util

import org.junit.Assert.*
import org.junit.Test

class LogMessageTransformerTest {

    @Test
    fun `needsTransformation returns true for messages with placeholders`() {
        assertTrue(LogMessageTransformer.needsTransformation("x={x}"))
        assertTrue(LogMessageTransformer.needsTransformation("{a} and {b}"))
        assertTrue(LogMessageTransformer.needsTransformation("value={obj.getValue()}"))
    }

    @Test
    fun `needsTransformation returns false for messages without placeholders`() {
        assertFalse(LogMessageTransformer.needsTransformation("simple message"))
        assertFalse(LogMessageTransformer.needsTransformation("\"x=\" + x"))
        assertFalse(LogMessageTransformer.needsTransformation(""))
    }

    @Test
    fun `needsTransformation returns false for escaped braces`() {
        assertFalse(LogMessageTransformer.needsTransformation("\\{not a placeholder\\}"))
    }

    @Test
    fun `parseLogMessage handles simple placeholder`() {
        val parts = LogMessageTransformer.parseLogMessage("x={x}")
        assertEquals(2, parts.size)
        assertEquals(LogMessagePart.Literal("x="), parts[0])
        assertEquals(LogMessagePart.Expression("x"), parts[1])
    }

    @Test
    fun `parseLogMessage handles multiple placeholders`() {
        val parts = LogMessageTransformer.parseLogMessage("x={x}, y={y}")
        assertEquals(4, parts.size)
        assertEquals(LogMessagePart.Literal("x="), parts[0])
        assertEquals(LogMessagePart.Expression("x"), parts[1])
        assertEquals(LogMessagePart.Literal(", y="), parts[2])
        assertEquals(LogMessagePart.Expression("y"), parts[3])
    }

    @Test
    fun `parseLogMessage handles trailing literal`() {
        val parts = LogMessageTransformer.parseLogMessage("{x} meters")
        assertEquals(2, parts.size)
        assertEquals(LogMessagePart.Expression("x"), parts[0])
        assertEquals(LogMessagePart.Literal(" meters"), parts[1])
    }

    @Test
    fun `parseLogMessage handles method calls in expression`() {
        val parts = LogMessageTransformer.parseLogMessage("size={list.size()}")
        assertEquals(2, parts.size)
        assertEquals(LogMessagePart.Literal("size="), parts[0])
        assertEquals(LogMessagePart.Expression("list.size()"), parts[1])
    }

    @Test
    fun `parseLogMessage handles escaped braces`() {
        val parts = LogMessageTransformer.parseLogMessage("\\{escaped\\}")
        assertEquals(1, parts.size)
        assertEquals(LogMessagePart.Literal("{escaped}"), parts[0])
    }

    @Test
    fun `parseLogMessage handles empty braces as literal`() {
        val parts = LogMessageTransformer.parseLogMessage("empty={}")
        assertEquals(2, parts.size)
        assertEquals(LogMessagePart.Literal("empty="), parts[0])
        assertEquals(LogMessagePart.Literal("{}"), parts[1])
    }

    @Test
    fun `parseLogMessage handles expression only`() {
        val parts = LogMessageTransformer.parseLogMessage("{variable}")
        assertEquals(1, parts.size)
        assertEquals(LogMessagePart.Expression("variable"), parts[0])
    }

    @Test
    fun `parseLogMessage handles literal only`() {
        val parts = LogMessageTransformer.parseLogMessage("just text")
        assertEquals(1, parts.size)
        assertEquals(LogMessagePart.Literal("just text"), parts[0])
    }
}

class JavaLogExpressionFormatterTest {

    private val formatter = JavaLogExpressionFormatter

    @Test
    fun `formats simple expression`() {
        val parts = listOf(
            LogMessagePart.Literal("x="),
            LogMessagePart.Expression("x")
        )
        assertEquals("\"x=\" + (x)", formatter.format(parts))
    }

    @Test
    fun `formats multiple expressions`() {
        val parts = listOf(
            LogMessagePart.Literal("x="),
            LogMessagePart.Expression("x"),
            LogMessagePart.Literal(", y="),
            LogMessagePart.Expression("y")
        )
        assertEquals("\"x=\" + (x) + \", y=\" + (y)", formatter.format(parts))
    }

    @Test
    fun `escapes quotes in literal`() {
        val parts = listOf(
            LogMessagePart.Literal("value=\""),
            LogMessagePart.Expression("x"),
            LogMessagePart.Literal("\"")
        )
        assertEquals("\"value=\\\"\" + (x) + \"\\\"\"", formatter.format(parts))
    }

    @Test
    fun `formats empty parts list`() {
        assertEquals("\"\"", formatter.format(emptyList()))
    }

    @Test
    fun `formats expression only`() {
        val parts = listOf(LogMessagePart.Expression("x"))
        assertEquals("(x)", formatter.format(parts))
    }
}

class KotlinLogExpressionFormatterTest {

    private val formatter = KotlinLogExpressionFormatter

    @Test
    fun `formats simple identifier with dollar sign`() {
        val parts = listOf(
            LogMessagePart.Literal("x="),
            LogMessagePart.Expression("x")
        )
        assertEquals("\"x=\$x\"", formatter.format(parts))
    }

    @Test
    fun `formats complex expression with braces`() {
        val parts = listOf(
            LogMessagePart.Literal("size="),
            LogMessagePart.Expression("list.size()")
        )
        assertEquals("\"size=\${list.size()}\"", formatter.format(parts))
    }

    @Test
    fun `formats multiple expressions`() {
        val parts = listOf(
            LogMessagePart.Literal("x="),
            LogMessagePart.Expression("x"),
            LogMessagePart.Literal(", y="),
            LogMessagePart.Expression("y")
        )
        assertEquals("\"x=\$x, y=\$y\"", formatter.format(parts))
    }

    @Test
    fun `escapes dollar sign in literal`() {
        val parts = listOf(
            LogMessagePart.Literal("cost=\$"),
            LogMessagePart.Expression("amount")
        )
        assertEquals("\"cost=\\\$\$amount\"", formatter.format(parts))
    }

    @Test
    fun `formats empty parts list`() {
        assertEquals("\"\"", formatter.format(emptyList()))
    }
}

class PythonLogExpressionFormatterTest {

    private val formatter = PythonLogExpressionFormatter

    @Test
    fun `formats with f-string`() {
        val parts = listOf(
            LogMessagePart.Literal("x="),
            LogMessagePart.Expression("x")
        )
        assertEquals("f\"x={x}\"", formatter.format(parts))
    }

    @Test
    fun `formats multiple expressions`() {
        val parts = listOf(
            LogMessagePart.Literal("x="),
            LogMessagePart.Expression("x"),
            LogMessagePart.Literal(", y="),
            LogMessagePart.Expression("y")
        )
        assertEquals("f\"x={x}, y={y}\"", formatter.format(parts))
    }

    @Test
    fun `escapes braces in literal`() {
        val parts = listOf(
            LogMessagePart.Literal("dict={key: "),
            LogMessagePart.Expression("value"),
            LogMessagePart.Literal("}")
        )
        assertEquals("f\"dict={{key: {value}}}\"", formatter.format(parts))
    }

    @Test
    fun `formats empty parts list`() {
        assertEquals("\"\"", formatter.format(emptyList()))
    }
}

class JavaScriptLogExpressionFormatterTest {

    private val formatter = JavaScriptLogExpressionFormatter

    @Test
    fun `formats with template literal`() {
        val parts = listOf(
            LogMessagePart.Literal("x="),
            LogMessagePart.Expression("x")
        )
        assertEquals("`x=\${x}`", formatter.format(parts))
    }

    @Test
    fun `formats multiple expressions`() {
        val parts = listOf(
            LogMessagePart.Literal("x="),
            LogMessagePart.Expression("x"),
            LogMessagePart.Literal(", y="),
            LogMessagePart.Expression("y")
        )
        assertEquals("`x=\${x}, y=\${y}`", formatter.format(parts))
    }

    @Test
    fun `escapes backtick in literal`() {
        val parts = listOf(
            LogMessagePart.Literal("code=`"),
            LogMessagePart.Expression("x"),
            LogMessagePart.Literal("`")
        )
        assertEquals("`code=\\`\${x}\\``", formatter.format(parts))
    }

    @Test
    fun `formats empty parts list`() {
        assertEquals("\"\"", formatter.format(emptyList()))
    }
}

class RubyLogExpressionFormatterTest {

    private val formatter = RubyLogExpressionFormatter

    @Test
    fun `formats with string interpolation`() {
        val parts = listOf(
            LogMessagePart.Literal("x="),
            LogMessagePart.Expression("x")
        )
        assertEquals("\"x=#{x}\"", formatter.format(parts))
    }

    @Test
    fun `formats multiple expressions`() {
        val parts = listOf(
            LogMessagePart.Literal("x="),
            LogMessagePart.Expression("x"),
            LogMessagePart.Literal(", y="),
            LogMessagePart.Expression("y")
        )
        assertEquals("\"x=#{x}, y=#{y}\"", formatter.format(parts))
    }

    @Test
    fun `formats empty parts list`() {
        assertEquals("\"\"", formatter.format(emptyList()))
    }
}

class CSharpLogExpressionFormatterTest {

    private val formatter = CSharpLogExpressionFormatter

    @Test
    fun `formats with interpolated string`() {
        val parts = listOf(
            LogMessagePart.Literal("x="),
            LogMessagePart.Expression("x")
        )
        assertEquals("\$\"x={x}\"", formatter.format(parts))
    }

    @Test
    fun `formats multiple expressions`() {
        val parts = listOf(
            LogMessagePart.Literal("x="),
            LogMessagePart.Expression("x"),
            LogMessagePart.Literal(", y="),
            LogMessagePart.Expression("y")
        )
        assertEquals("\$\"x={x}, y={y}\"", formatter.format(parts))
    }

    @Test
    fun `escapes braces in literal`() {
        val parts = listOf(
            LogMessagePart.Literal("json={\"key\": "),
            LogMessagePart.Expression("value"),
            LogMessagePart.Literal("}")
        )
        assertEquals("\$\"json={{\\\"key\\\": {value}}}\"", formatter.format(parts))
    }

    @Test
    fun `formats empty parts list`() {
        assertEquals("\"\"", formatter.format(emptyList()))
    }
}

class LogExpressionFormattersTest {

    @Test
    fun `getFormatter returns Java formatter for java language`() {
        assertTrue(LogExpressionFormatters.getFormatter("Java") is JavaLogExpressionFormatter)
        assertTrue(LogExpressionFormatters.getFormatter("java") is JavaLogExpressionFormatter)
    }

    @Test
    fun `getFormatter returns Kotlin formatter for kotlin language`() {
        assertTrue(LogExpressionFormatters.getFormatter("kotlin") is KotlinLogExpressionFormatter)
        assertTrue(LogExpressionFormatters.getFormatter("Kotlin") is KotlinLogExpressionFormatter)
    }

    @Test
    fun `getFormatter returns Python formatter for python language`() {
        assertTrue(LogExpressionFormatters.getFormatter("Python") is PythonLogExpressionFormatter)
        assertTrue(LogExpressionFormatters.getFormatter("python") is PythonLogExpressionFormatter)
    }

    @Test
    fun `getFormatter returns JavaScript formatter for js and ts languages`() {
        assertTrue(LogExpressionFormatters.getFormatter("JavaScript") is JavaScriptLogExpressionFormatter)
        assertTrue(LogExpressionFormatters.getFormatter("TypeScript") is JavaScriptLogExpressionFormatter)
        assertTrue(LogExpressionFormatters.getFormatter("ECMAScript 6") is JavaScriptLogExpressionFormatter)
    }

    @Test
    fun `getFormatter returns Java formatter for unknown languages`() {
        assertTrue(LogExpressionFormatters.getFormatter("unknown") is JavaLogExpressionFormatter)
        assertTrue(LogExpressionFormatters.getFormatter("SomeNewLanguage") is JavaLogExpressionFormatter)
    }

    @Test
    fun `getFormatter returns Groovy and Scala as Java-style`() {
        assertTrue(LogExpressionFormatters.getFormatter("Groovy") is JavaLogExpressionFormatter)
        assertTrue(LogExpressionFormatters.getFormatter("Scala") is JavaLogExpressionFormatter)
    }
}
