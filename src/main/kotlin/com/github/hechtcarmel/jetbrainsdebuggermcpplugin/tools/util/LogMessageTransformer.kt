package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.util

import com.intellij.lang.LanguageUtil
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.vfs.VirtualFile

/**
 * Transforms user-friendly log message syntax ({expression}) to language-specific expressions.
 *
 * Users can write log messages like "x={x}, y={y}" which will be transformed to:
 * - Java: "x=" + (x) + ", y=" + (y)
 * - Kotlin: "x=$x, y=$y"
 * - Python: f"x={x}, y={y}"
 * - JavaScript/TypeScript: `x=${x}, y=${y}`
 */
object LogMessageTransformer {

    private val EXPRESSION_PATTERN = Regex("""(?<!\\)\{([^{}]*(?:\{[^{}]*\}[^{}]*)*)\}""")

    /**
     * Transforms a log message with {expression} placeholders to a language-specific expression.
     *
     * @param message The log message with {expression} placeholders
     * @param virtualFile The file where the breakpoint is set (used for language detection)
     * @return The transformed expression suitable for the target language
     */
    fun transform(message: String, virtualFile: VirtualFile): String {
        if (!needsTransformation(message)) {
            return message
        }

        val parts = parseLogMessage(message)
        val language = detectLanguage(virtualFile)
        val formatter = LogExpressionFormatters.getFormatter(language)

        return formatter.format(parts)
    }

    /**
     * Checks if the message contains {expression} placeholders that need transformation.
     */
    fun needsTransformation(message: String): Boolean {
        return EXPRESSION_PATTERN.containsMatchIn(message)
    }

    /**
     * Detects the programming language from a virtual file.
     *
     * @return Language identifier (e.g., "Java", "kotlin", "Python", "JavaScript")
     */
    fun detectLanguage(virtualFile: VirtualFile): String {
        val fileType = virtualFile.fileType
        if (fileType is LanguageFileType) {
            return fileType.language.id
        }
        return when (virtualFile.extension?.lowercase()) {
            "java" -> "Java"
            "kt", "kts" -> "kotlin"
            "py" -> "Python"
            "js" -> "JavaScript"
            "ts" -> "TypeScript"
            "jsx" -> "JSX"
            "tsx" -> "TypeScript JSX"
            "rb" -> "ruby"
            "php" -> "PHP"
            "go" -> "go"
            "rs" -> "Rust"
            "swift" -> "Swift"
            "c", "h" -> "ObjectiveC"
            "cpp", "hpp", "cc", "cxx" -> "ObjectiveC"
            "cs" -> "C#"
            "scala" -> "Scala"
            "groovy" -> "Groovy"
            else -> "unknown"
        }
    }

    /**
     * Parses a log message into a list of literal and expression parts.
     *
     * Supports:
     * - Basic placeholders: {x}
     * - Nested braces for method calls: {map.get("key")}
     * - Escaped braces: \{ and \} are treated as literal characters
     */
    fun parseLogMessage(message: String): List<LogMessagePart> {
        val parts = mutableListOf<LogMessagePart>()
        var lastEnd = 0

        for (match in EXPRESSION_PATTERN.findAll(message)) {
            // Add literal part before this expression
            if (match.range.first > lastEnd) {
                val literalText = message.substring(lastEnd, match.range.first)
                    .replace("\\{", "{")
                    .replace("\\}", "}")
                if (literalText.isNotEmpty()) {
                    parts.add(LogMessagePart.Literal(literalText))
                }
            }

            // Add expression part
            val expression = match.groupValues[1]
            if (expression.isNotEmpty()) {
                parts.add(LogMessagePart.Expression(expression))
            } else {
                // Empty braces {} are treated as literal text
                parts.add(LogMessagePart.Literal("{}"))
            }

            lastEnd = match.range.last + 1
        }

        // Add remaining literal part
        if (lastEnd < message.length) {
            val literalText = message.substring(lastEnd)
                .replace("\\{", "{")
                .replace("\\}", "}")
            if (literalText.isNotEmpty()) {
                parts.add(LogMessagePart.Literal(literalText))
            }
        }

        return parts
    }
}

/**
 * Represents a part of a log message - either a literal string or an expression to evaluate.
 */
sealed class LogMessagePart {
    /**
     * A literal text part that should be included as-is in the output.
     */
    data class Literal(val text: String) : LogMessagePart()

    /**
     * An expression that should be evaluated and its result included in the output.
     */
    data class Expression(val expression: String) : LogMessagePart()
}
