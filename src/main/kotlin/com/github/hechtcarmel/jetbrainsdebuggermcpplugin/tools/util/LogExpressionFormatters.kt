package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.util

/**
 * Factory for getting language-specific log expression formatters.
 */
object LogExpressionFormatters {

    /**
     * Returns the appropriate formatter for the given language.
     *
     * Languages without a formatter get [UnsupportedLogExpressionFormatter] rather than a Java
     * fallback: native debuggers (LLDB/GDB/Delve) cannot evaluate Java-style string concatenation,
     * so the old fallback produced expressions that failed silently in the IDE console at hit time.
     * Failing at `set_breakpoint` names the limitation while the agent can still react to it.
     */
    fun getFormatter(language: String): LogExpressionFormatter {
        return when (language.lowercase()) {
            "java", "groovy", "scala" -> JavaLogExpressionFormatter
            "kotlin" -> KotlinLogExpressionFormatter
            "python" -> PythonLogExpressionFormatter
            "javascript", "typescript", "jsx", "typescript jsx", "ecmascript 6" -> JavaScriptLogExpressionFormatter
            "ruby" -> RubyLogExpressionFormatter
            "php" -> PhpLogExpressionFormatter
            "c#" -> CSharpLogExpressionFormatter
            else -> UnsupportedLogExpressionFormatter(language)
        }
    }
}

/**
 * Thrown when a log message uses `{expression}` placeholders for a language whose debugger cannot
 * evaluate the string-building expression any formatter would emit.
 */
class UnsupportedLogMessageException(message: String) : IllegalArgumentException(message)

/**
 * Interface for language-specific log expression formatters.
 */
interface LogExpressionFormatter {
    /**
     * Formats a list of log message parts into a language-specific expression.
     */
    fun format(parts: List<LogMessagePart>): String

    /**
     * Escapes special characters in a literal string for the target language.
     */
    fun escapeLiteral(text: String): String
}

/**
 * Java formatter: "literal" + (expr) + "literal"
 */
object JavaLogExpressionFormatter : LogExpressionFormatter {

    override fun format(parts: List<LogMessagePart>): String {
        if (parts.isEmpty()) return "\"\""

        val segments = mutableListOf<String>()
        // A leading expression followed by more parts would make Java's `+` left-associate over
        // non-strings first: "{a}{b}" with two ints must print "34", not 7. An empty leading
        // string literal forces concatenation from the first operand on.
        if (parts.size > 1 && parts.first() is LogMessagePart.Expression) {
            segments.add("\"\"")
        }
        for (part in parts) {
            when (part) {
                is LogMessagePart.Literal -> {
                    segments.add("\"${escapeLiteral(part.text)}\"")
                }
                is LogMessagePart.Expression -> {
                    segments.add("(${part.expression})")
                }
            }
        }
        return segments.joinToString(" + ")
    }

    override fun escapeLiteral(text: String): String {
        return text
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }
}

/**
 * Fallback for languages debugged by native evaluators (Rust, Go, Swift, C, C++) and anything
 * else without a formatter of its own.
 *
 * A single bare `{expr}` is passed through as the raw expression — every debugger can evaluate
 * that. Anything needing string formatting is rejected: LLDB/GDB/Delve cannot reliably call
 * `format!`/`fmt.Sprintf`, so emitting them would just trade one unevaluable expression for
 * another that fails at breakpoint-hit time instead of now.
 */
class UnsupportedLogExpressionFormatter(private val language: String) : LogExpressionFormatter {

    override fun format(parts: List<LogMessagePart>): String {
        val single = parts.singleOrNull() as? LogMessagePart.Expression
        if (single != null) {
            return single.expression
        }
        throw UnsupportedLogMessageException(
            "{expression} placeholders in log_message are not supported for $language: its debugger " +
                "cannot evaluate the generated string-formatting expression. Use a single bare " +
                "{expression} with no surrounding text, or pass a raw expression the debugger can evaluate."
        )
    }

    override fun escapeLiteral(text: String): String = text
}

/**
 * Kotlin formatter: "literal$expr" or "literal${expr}"
 * Uses simple $var for identifiers, ${expr} for complex expressions
 */
object KotlinLogExpressionFormatter : LogExpressionFormatter {

    private val SIMPLE_IDENTIFIER = Regex("^[a-zA-Z_][a-zA-Z0-9_]*$")

    override fun format(parts: List<LogMessagePart>): String {
        if (parts.isEmpty()) return "\"\""

        val sb = StringBuilder("\"")
        for (part in parts) {
            when (part) {
                is LogMessagePart.Literal -> {
                    sb.append(escapeLiteral(part.text))
                }
                is LogMessagePart.Expression -> {
                    if (SIMPLE_IDENTIFIER.matches(part.expression)) {
                        sb.append("\$${part.expression}")
                    } else {
                        sb.append("\${${part.expression}}")
                    }
                }
            }
        }
        sb.append("\"")
        return sb.toString()
    }

    override fun escapeLiteral(text: String): String {
        return text
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\$", "\\\$")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }
}

/**
 * Python formatter: f"literal{expr}"
 */
object PythonLogExpressionFormatter : LogExpressionFormatter {

    override fun format(parts: List<LogMessagePart>): String {
        if (parts.isEmpty()) return "\"\""

        val sb = StringBuilder("f\"")
        for (part in parts) {
            when (part) {
                is LogMessagePart.Literal -> {
                    sb.append(escapeLiteral(part.text))
                }
                is LogMessagePart.Expression -> {
                    sb.append("{${part.expression}}")
                }
            }
        }
        sb.append("\"")
        return sb.toString()
    }

    override fun escapeLiteral(text: String): String {
        return text
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("{", "{{")
            .replace("}", "}}")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }
}

/**
 * JavaScript/TypeScript formatter: `literal${expr}`
 */
object JavaScriptLogExpressionFormatter : LogExpressionFormatter {

    override fun format(parts: List<LogMessagePart>): String {
        if (parts.isEmpty()) return "\"\""

        val sb = StringBuilder("`")
        for (part in parts) {
            when (part) {
                is LogMessagePart.Literal -> {
                    sb.append(escapeLiteral(part.text))
                }
                is LogMessagePart.Expression -> {
                    sb.append("\${${part.expression}}")
                }
            }
        }
        sb.append("`")
        return sb.toString()
    }

    override fun escapeLiteral(text: String): String {
        return text
            .replace("\\", "\\\\")
            .replace("`", "\\`")
            .replace("\$", "\\\$")
    }
}

/**
 * Ruby formatter: "literal#{expr}"
 */
object RubyLogExpressionFormatter : LogExpressionFormatter {

    override fun format(parts: List<LogMessagePart>): String {
        if (parts.isEmpty()) return "\"\""

        val sb = StringBuilder("\"")
        for (part in parts) {
            when (part) {
                is LogMessagePart.Literal -> {
                    sb.append(escapeLiteral(part.text))
                }
                is LogMessagePart.Expression -> {
                    sb.append("#{${part.expression}}")
                }
            }
        }
        sb.append("\"")
        return sb.toString()
    }

    override fun escapeLiteral(text: String): String {
        return text
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("#", "\\#")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }
}

/**
 * PHP formatter: "literal" . (expr) . "literal" or using double-quoted string interpolation
 * Uses string concatenation for safety
 */
object PhpLogExpressionFormatter : LogExpressionFormatter {

    override fun format(parts: List<LogMessagePart>): String {
        if (parts.isEmpty()) return "\"\""

        val segments = mutableListOf<String>()
        for (part in parts) {
            when (part) {
                is LogMessagePart.Literal -> {
                    segments.add("\"${escapeLiteral(part.text)}\"")
                }
                is LogMessagePart.Expression -> {
                    segments.add("(${part.expression})")
                }
            }
        }
        return segments.joinToString(" . ")
    }

    override fun escapeLiteral(text: String): String {
        return text
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\$", "\\\$")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }
}

/**
 * C# formatter: $"literal{expr}" (interpolated string)
 */
object CSharpLogExpressionFormatter : LogExpressionFormatter {

    override fun format(parts: List<LogMessagePart>): String {
        if (parts.isEmpty()) return "\"\""

        val sb = StringBuilder("\$\"")
        for (part in parts) {
            when (part) {
                is LogMessagePart.Literal -> {
                    sb.append(escapeLiteral(part.text))
                }
                is LogMessagePart.Expression -> {
                    sb.append("{${part.expression}}")
                }
            }
        }
        sb.append("\"")
        return sb.toString()
    }

    override fun escapeLiteral(text: String): String {
        return text
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("{", "{{")
            .replace("}", "}}")
    }
}
