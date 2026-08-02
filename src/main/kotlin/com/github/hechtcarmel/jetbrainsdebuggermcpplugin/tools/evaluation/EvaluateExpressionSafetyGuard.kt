package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.evaluation

import com.intellij.openapi.project.Project
import com.intellij.xdebugger.XSourcePosition

object EvaluateExpressionSafetyGuard {
    private const val MAX_SCANNED_EXPRESSION_LENGTH = 10_000

    data class Context(
        val project: Project,
        val sourcePosition: XSourcePosition?
    )

    private data class BlocklistRule(
        val id: String,
        val message: String,
        val regex: Regex
    )

    /**
     * Outcome of scanning an expression: either the expression with comments and string literals
     * blanked out (safe to run the regex rules over), or a rejection.
     *
     * Rejection — not blanking — is deliberate for interpolated templates, backtick literals and
     * unterminated strings. Interpolated segments are *code* in Kotlin/JS/Python/Ruby, so blanking
     * them removes the payload **and** every token the blocklist and read-only rules look for:
     * `"${Runtime.getRuntime().exec(cmd)}"` used to scan as an inert literal and pass even the
     * strictest mode.
     */
    private sealed interface ExpressionScan {
        data class Stripped(val text: String) : ExpressionScan
        data class Rejected(val ruleId: String, val message: String, val token: String) : ExpressionScan
    }

    private val blocklistRules = listOf(
        BlocklistRule(
            id = "process-execution",
            message = "process execution APIs are not allowed",
            regex = Regex(
                """\b(?:java\.lang\.)?Runtime\s*\.\s*getRuntime\s*\(\s*\)\s*\.\s*exec\s*\(|\b(?:java\.lang\.)?ProcessBuilder\s*\(|\.\s*exec\s*\("""
            )
        ),
        BlocklistRule(
            id = "jvm-termination",
            message = "JVM termination APIs are not allowed",
            regex = Regex("""\b(?:java\.lang\.)?System\s*\.\s*(?:exit|halt)\s*\(|\b(?:java\.lang\.)?Runtime\s*\.\s*getRuntime\s*\(\s*\)\s*\.\s*(?:exit|halt)\s*\(""")
        ),
        BlocklistRule(
            id = "filesystem-access",
            message = "filesystem access APIs are not allowed",
            regex = Regex(
                """\b(?:java\.nio\.file\.)?Files\s*\.|\b(?:java\.io\.)?(?:File|FileInputStream|FileOutputStream|FileReader|FileWriter|RandomAccessFile|PrintWriter)\s*\("""
            )
        ),
        BlocklistRule(
            id = "network-access",
            message = "network access APIs are not allowed",
            regex = Regex(
                """\b(?:java\.net\.)?(?:Socket|ServerSocket|DatagramSocket|URL|URI|URLConnection|HttpURLConnection|InetAddress)\b|\b(?:java\.net\.http\.)?HttpClient\b|\.\s*(?:openConnection|openStream|connect|send)\s*\("""
            )
        ),
        BlocklistRule(
            id = "reflection-access",
            message = "reflection and access-bypass APIs are not allowed",
            regex = Regex(
                """\b(?:java\.lang\.)?Class\s*\.\s*forName\s*\(|\b(?:java\.lang\.invoke\.)?MethodHandles\b|\b(?:sun\.misc\.|jdk\.internal\.misc\.)?Unsafe\b|\bjava\.lang\.reflect\.|\bClassLoader\b|\.\s*(?:setAccessible|getDeclaredMethod|getDeclaredMethods|getDeclaredField|getDeclaredFields|getDeclaredConstructor|getDeclaredConstructors|getClassLoader)\s*\("""
            )
        ),
        BlocklistRule(
            id = "native-code",
            message = "native library loading APIs are not allowed",
            regex = Regex("""\b(?:java\.lang\.)?System\s*\.\s*(?:load|loadLibrary)\s*\(|\b(?:java\.lang\.)?Runtime\s*\.\s*getRuntime\s*\(\s*\)\s*\.\s*(?:load|loadLibrary)\s*\(""")
        ),
        BlocklistRule(
            id = "environment-access",
            message = "environment and system property access is not allowed",
            regex = Regex("""\b(?:java\.lang\.)?System\s*\.\s*(?:getenv|getProperties|getProperty|setProperty|clearProperty)\s*\(""")
        )
    )

    fun validate(
        expression: String,
        mode: EvaluateExpressionSafetyMode,
        context: Context?,
        customRules: List<CustomEvaluateExpressionBlockRule> = emptyList()
    ): EvaluationSafetyViolation? {
        if (mode == EvaluateExpressionSafetyMode.UNRESTRICTED) return null

        // Reject rather than truncate: a truncated scan would let a long no-op prefix push the
        // payload past the analyzed window and disable the guard entirely.
        if (expression.length > MAX_SCANNED_EXPRESSION_LENGTH) {
            return EvaluationSafetyViolation(
                mode = mode,
                ruleId = "expression-too-long",
                message = "expressions longer than $MAX_SCANNED_EXPRESSION_LENGTH characters cannot be safety-checked"
            )
        }

        val searchableExpression = when (val scan = scanExpression(expression)) {
            is ExpressionScan.Rejected -> return EvaluationSafetyViolation(
                mode = mode,
                ruleId = scan.ruleId,
                message = scan.message,
                token = scan.token
            )
            is ExpressionScan.Stripped -> scan.text
        }

        checkBlocklist(searchableExpression, mode)?.let { return it }
        checkCustomRules(searchableExpression, mode, customRules)?.let { return it }

        if (mode != EvaluateExpressionSafetyMode.READ_ONLY) return null

        if (context?.sourcePosition?.file?.extension == "java") {
            val javaViolation = runJavaAnalyzer(context.project, expression, context.sourcePosition, searchableExpression)
            if (javaViolation != null) return javaViolation
            return null
        }

        return checkGenericReadOnly(searchableExpression, mode)
    }

    private fun checkBlocklist(
        searchableExpression: String,
        mode: EvaluateExpressionSafetyMode
    ): EvaluationSafetyViolation? {
        for (rule in blocklistRules) {
            val match = rule.regex.find(searchableExpression)
            if (match != null) {
                return EvaluationSafetyViolation(
                    mode = mode,
                    ruleId = rule.id,
                    message = rule.message,
                    token = match.value.trim()
                )
            }
        }
        return null
    }

    private fun checkCustomRules(
        searchableExpression: String,
        mode: EvaluateExpressionSafetyMode,
        customRules: List<CustomEvaluateExpressionBlockRule>
    ): EvaluationSafetyViolation? {
        customRules.forEachIndexed { index, rule ->
            val pattern = rule.pattern.trim()
            if (!rule.enabled || pattern.isBlank()) return@forEachIndexed

            val regex = try {
                pattern.toRegex()
            } catch (_: IllegalArgumentException) {
                return@forEachIndexed
            }

            val match = regex.find(searchableExpression) ?: return@forEachIndexed
            val reason = rule.reason.trim()
            return EvaluationSafetyViolation(
                mode = mode,
                ruleId = "custom-regex",
                message = if (reason.isNotBlank()) {
                    "custom regex rule #${index + 1} blocked this expression: $reason"
                } else {
                    "custom regex rule #${index + 1} blocked this expression"
                },
                token = match.value.trim(),
                customRulePattern = pattern,
                customRuleReason = reason.takeIf { it.isNotBlank() }
            )
        }
        return null
    }

    private fun checkGenericReadOnly(
        searchableExpression: String,
        mode: EvaluateExpressionSafetyMode
    ): EvaluationSafetyViolation? {
        Regex("""[;\n\r]""").find(searchableExpression)?.let {
            return EvaluationSafetyViolation(
                mode = mode,
                ruleId = "read-only-code-fragment",
                message = "code fragments are not allowed in read-only mode",
                token = it.value
            )
        }

        Regex("""\+\+|--""").find(searchableExpression)?.let {
            return EvaluationSafetyViolation(
                mode = mode,
                ruleId = "read-only-increment",
                message = "increment and decrement operations are not allowed in read-only mode",
                token = it.value
            )
        }

        Regex("""\+=|-=|\*=|/=|%=|&=|\|=|\^=|<<=|>>=|>>>=|(?<![=!<>])=(?!=)""").find(searchableExpression)?.let {
            return EvaluationSafetyViolation(
                mode = mode,
                ruleId = "read-only-assignment",
                message = "assignment operations are not allowed in read-only mode",
                token = it.value
            )
        }

        Regex("""\bnew\s+[A-Za-z_$][\w$]*""").find(searchableExpression)?.let {
            return EvaluationSafetyViolation(
                mode = mode,
                ruleId = "read-only-constructor",
                message = "object construction is not allowed in read-only mode without a language-specific analyzer",
                token = it.value
            )
        }

        Regex("""(?:\.|::)?\s*[A-Za-z_$][\w$]*\s*\(""").find(searchableExpression)?.let {
            return EvaluationSafetyViolation(
                mode = mode,
                ruleId = "read-only-uncertain-method-call",
                message = "method calls are not allowed in read-only mode without a language-specific analyzer",
                token = it.value.trim()
            )
        }

        return null
    }

    private fun runJavaAnalyzer(
        project: Project,
        expression: String,
        sourcePosition: XSourcePosition?,
        searchableExpression: String
    ): EvaluationSafetyViolation? {
        return try {
            val analyzerClass = Class.forName(
                "com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.evaluation.JavaReadOnlyExpressionAnalyzer"
            )
            val analyzer = analyzerClass.getField("INSTANCE").get(null) as ReadOnlyExpressionAnalyzer
            analyzer.check(project, expression, sourcePosition)
        } catch (_: ReflectiveOperationException) {
            checkGenericReadOnly(searchableExpression, EvaluateExpressionSafetyMode.READ_ONLY)
        } catch (_: LinkageError) {
            checkGenericReadOnly(searchableExpression, EvaluateExpressionSafetyMode.READ_ONLY)
        }
    }

    /**
     * Blanks comments and plain string-literal contents so the regex rules only see code, and
     * rejects the constructs that cannot be blanked safely:
     *
     * - `${` / `#{` inside a double-quoted literal (Kotlin/Groovy/JS and Ruby/Groovy interpolation)
     * - backtick literals (JS template strings, Ruby shell execution)
     * - f-string prefixes (Python interpolation)
     * - an unterminated string literal, which would silently blank everything after it
     */
    private fun scanExpression(expression: String): ExpressionScan {
        val result = StringBuilder(expression.length)
        var index = 0
        var inLineComment = false
        var inBlockComment = false
        var inString = false
        var stringDelimiter = ' '
        var escaped = false

        while (index < expression.length) {
            val current = expression[index]
            val next = expression.getOrNull(index + 1)

            when {
                inLineComment -> {
                    if (current == '\n' || current == '\r') {
                        inLineComment = false
                        result.append(current)
                    } else {
                        result.append(' ')
                    }
                }
                inBlockComment -> {
                    if (current == '*' && next == '/') {
                        inBlockComment = false
                        result.append("  ")
                        index++
                    } else {
                        result.append(if (current == '\n' || current == '\r') current else ' ')
                    }
                }
                inString -> {
                    if (!escaped && stringDelimiter == '"' && (current == '$' || current == '#') && next == '{') {
                        return ExpressionScan.Rejected(
                            ruleId = "interpolated-string-template",
                            message = "interpolated string templates cannot be safety-checked because " +
                                "interpolated segments execute as code",
                            token = "$current{"
                        )
                    }
                    result.append(if (current == '\n' || current == '\r') current else ' ')
                    if (escaped) {
                        escaped = false
                    } else if (current == '\\') {
                        escaped = true
                    } else if (current == stringDelimiter) {
                        inString = false
                    }
                }
                current == '/' && next == '/' -> {
                    inLineComment = true
                    result.append("  ")
                    index++
                }
                current == '/' && next == '*' -> {
                    inBlockComment = true
                    result.append("  ")
                    index++
                }
                current == '`' -> {
                    return ExpressionScan.Rejected(
                        ruleId = "interpolated-string-template",
                        message = "backtick literals cannot be safety-checked because they can execute " +
                            "code (template strings, shell execution)",
                        token = "`"
                    )
                }
                current == '"' || current == '\'' -> {
                    if (isFStringPrefix(expression, index)) {
                        return ExpressionScan.Rejected(
                            ruleId = "interpolated-string-template",
                            message = "f-strings cannot be safety-checked because interpolated " +
                                "segments execute as code",
                            token = "f$current"
                        )
                    }
                    inString = true
                    stringDelimiter = current
                    escaped = false
                    result.append(' ')
                }
                else -> result.append(current)
            }

            index++
        }

        if (inString) {
            return ExpressionScan.Rejected(
                ruleId = "unterminated-string-literal",
                message = "the expression contains an unterminated string literal and cannot be " +
                    "safety-checked",
                token = stringDelimiter.toString()
            )
        }

        return ExpressionScan.Stripped(result.toString())
    }

    /**
     * True when the quote at [quoteIndex] is preceded by a short Python string-prefix letter run
     * containing `f`/`F` (`f"`, `rf'`, `FR"`, ...), i.e. the literal is an f-string.
     */
    private fun isFStringPrefix(expression: String, quoteIndex: Int): Boolean {
        var start = quoteIndex
        while (start > 0 && expression[start - 1].isLetter()) start--
        val run = expression.substring(start, quoteIndex)
        if (run.isEmpty() || run.length > 3) return false
        if (!run.any { it == 'f' || it == 'F' }) return false
        // A preceding identifier character means the letters are the tail of a longer identifier,
        // not a string prefix.
        val before = expression.getOrNull(start - 1)
        return before == null || (!before.isLetterOrDigit() && before != '_' && before != '$')
    }
}
