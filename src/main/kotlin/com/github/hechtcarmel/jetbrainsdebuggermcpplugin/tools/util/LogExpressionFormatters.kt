package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.util

/**
 * Factory for getting language-specific log expression formatters.
 */
object LogExpressionFormatters {

    /**
     * Returns the appropriate formatter for the given language.
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
            else -> JavaLogExpressionFormatter // Default to Java-style
        }
    }
}

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
