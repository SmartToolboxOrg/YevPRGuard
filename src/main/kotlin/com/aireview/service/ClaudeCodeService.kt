package com.aireview.service

import com.aireview.model.ReviewFinding
import com.aireview.model.ReviewRequest
import com.aireview.settings.ReviewSettings
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

data class PrDescription(val title: String, val description: String)

/**
 * Invokes the `claude` CLI as a subprocess to perform code review.
 *
 * Sends the diff via stdin to avoid OS argument length limits. Parses the
 * JSON response into List<ReviewFinding> compatible with the existing
 * FindingsManager / annotator pipeline.
 */
class ClaudeCodeService {

    private val log = Logger.getInstance(ClaudeCodeService::class.java)

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * Run a code review by invoking the claude CLI.
     *
     * @param request The review request containing diff and metadata
     * @param indicator Optional progress indicator for cancellation support
     * @return List of findings from the review
     */
    fun review(request: ReviewRequest, indicator: ProgressIndicator? = null): List<ReviewFinding> {
        val settings = ReviewSettings.getInstance().state
        val cliPath = resolveCliPath(settings.claudeCliPath)
        val timeoutSeconds = settings.requestTimeoutSeconds.toLong()

        val args = mutableListOf(cliPath, "-p", "--output-format", "json", "--max-turns", "1")
        if (settings.claudeModel.isNotBlank()) {
            args.add("--model")
            args.add(settings.claudeModel)
        }

        val prompt = buildPrompt(request)

        log.info("Invoking claude CLI: ${args.joinToString(" ")} (prompt ${prompt.length} chars)")

        val process = ProcessBuilder(args)
            .redirectErrorStream(false)
            .start()

        // Write prompt to stdin
        process.outputStream.use { os ->
            os.write(prompt.toByteArray(Charsets.UTF_8))
            os.flush()
        }

        // Read stderr in background to prevent deadlock
        val stderrFuture = CompletableFuture.supplyAsync {
            process.errorStream.bufferedReader().readText()
        }

        indicator?.checkCanceled()

        val stdout = process.inputStream.bufferedReader().readText()

        val exited = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!exited) {
            process.destroyForcibly()
            throw ClaudeCliException("Claude CLI timed out after ${timeoutSeconds}s. Increase timeout in Settings > Tools > AI Review.")
        }

        val exitCode = process.exitValue()
        val stderr = stderrFuture.get(5, TimeUnit.SECONDS)

        if (exitCode != 0) {
            log.warn("Claude CLI failed (exit $exitCode): $stderr")
            if (stderr.contains("not authenticated", ignoreCase = true) ||
                stderr.contains("authenticate", ignoreCase = true)) {
                throw ClaudeCliException(
                    "Claude CLI is not authenticated. Run 'claude' in a terminal to log in, then retry."
                )
            }
            throw ClaudeCliException("Claude CLI exited with code $exitCode:\n$stderr")
        }

        indicator?.checkCanceled()

        return parseResponse(stdout)
    }

    /**
     * Generate a PR title and markdown description using Claude CLI.
     */
    fun generatePrDescription(
        diff: String,
        findings: List<ReviewFinding>,
        currentTitle: String,
        indicator: ProgressIndicator? = null
    ): PrDescription {
        val settings = ReviewSettings.getInstance().state
        val cliPath = resolveCliPath(settings.claudeCliPath)
        val timeoutSeconds = settings.requestTimeoutSeconds.toLong()

        val args = mutableListOf(cliPath, "-p", "--output-format", "json", "--max-turns", "1")
        if (settings.claudeModel.isNotBlank()) {
            args.add("--model")
            args.add(settings.claudeModel)
        }

        val prompt = buildPrDescriptionPrompt(diff, findings, currentTitle)

        log.info("Invoking claude CLI for PR description (prompt ${prompt.length} chars)")

        val process = ProcessBuilder(args)
            .redirectErrorStream(false)
            .start()

        process.outputStream.use { os ->
            os.write(prompt.toByteArray(Charsets.UTF_8))
            os.flush()
        }

        val stderrFuture = CompletableFuture.supplyAsync {
            process.errorStream.bufferedReader().readText()
        }

        indicator?.checkCanceled()

        val stdout = process.inputStream.bufferedReader().readText()

        val exited = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!exited) {
            process.destroyForcibly()
            throw ClaudeCliException("Claude CLI timed out after ${timeoutSeconds}s.")
        }

        val exitCode = process.exitValue()
        val stderr = stderrFuture.get(5, TimeUnit.SECONDS)

        if (exitCode != 0) {
            log.warn("Claude CLI failed (exit $exitCode): $stderr")
            throw ClaudeCliException("Claude CLI exited with code $exitCode:\n$stderr")
        }

        indicator?.checkCanceled()

        return parsePrDescriptionResponse(stdout, currentTitle)
    }

    internal fun buildPrDescriptionPrompt(
        diff: String,
        findings: List<ReviewFinding>,
        currentTitle: String
    ): String {
        return buildString {
            appendLine("You are a senior software engineer writing a PR title and description.")
            appendLine()
            appendLine("Given the diff below, generate a concise PR title and a markdown description.")
            appendLine()
            appendLine("<instructions>")
            appendLine("Return ONLY a JSON object with two fields:")
            appendLine("- \"title\": short PR title, max 72 chars, imperative mood (e.g. \"Add user auth middleware\")")
            appendLine("- \"description\": markdown body with these sections:")
            appendLine("  ## Summary")
            appendLine("  2-3 bullet points describing what this PR does and why")
            appendLine("  ## Changes")
            appendLine("  Per-file or per-area breakdown of what changed")
            appendLine("  If there are structural changes (new classes, interface changes, data flow modifications),")
            appendLine("  include a ```mermaid diagram showing the relationships.")
            appendLine()
            appendLine("Return ONLY the JSON object. No markdown fences, no explanation text.")
            appendLine("</instructions>")
            appendLine()
            appendLine("Current PR title: $currentTitle")
            appendLine()

            if (findings.isNotEmpty()) {
                appendLine("<review-findings>")
                for (f in findings) {
                    appendLine("- [${f.severity}] ${f.filePath}:${f.line ?: "?"} — ${f.message}")
                }
                appendLine("</review-findings>")
                appendLine()
            }

            appendLine("<diff>")
            appendLine(diff)
            appendLine("</diff>")
        }
    }

    private fun parsePrDescriptionResponse(stdout: String, fallbackTitle: String): PrDescription {
        if (stdout.isBlank()) {
            return PrDescription(fallbackTitle, "")
        }

        try {
            val envelope = json.parseToJsonElement(stdout).jsonObject
            val resultText = envelope["result"]?.jsonPrimitive?.content ?: stdout
            return parsePrDescriptionJson(resultText, fallbackTitle)
        } catch (e: Exception) {
            log.warn("Failed to parse as CLI envelope for PR description, trying raw: ${e.message}")
            return parsePrDescriptionJson(stdout, fallbackTitle)
        }
    }

    private fun parsePrDescriptionJson(text: String, fallbackTitle: String): PrDescription {
        val cleaned = text
            .replace(Regex("^```(?:json)?\\s*", RegexOption.MULTILINE), "")
            .replace(Regex("^```\\s*$", RegexOption.MULTILINE), "")
            .trim()

        try {
            val obj = json.parseToJsonElement(cleaned).jsonObject
            return PrDescription(
                title = obj["title"]?.jsonPrimitive?.content ?: fallbackTitle,
                description = obj["description"]?.jsonPrimitive?.content ?: ""
            )
        } catch (e: Exception) {
            log.info("Direct JSON parse failed, trying regex extraction: ${e.message}")
        }

        // Fallback: extract JSON object via regex
        val objMatch = Regex("\\{[\\s\\S]*}").find(cleaned)
        if (objMatch != null) {
            try {
                val obj = json.parseToJsonElement(objMatch.value).jsonObject
                return PrDescription(
                    title = obj["title"]?.jsonPrimitive?.content ?: fallbackTitle,
                    description = obj["description"]?.jsonPrimitive?.content ?: ""
                )
            } catch (e: Exception) {
                log.warn("Regex-extracted JSON parse also failed: ${e.message}")
            }
        }

        log.warn("Could not parse PR description response, using fallback")
        return PrDescription(fallbackTitle, "")
    }

    private fun buildPrompt(request: ReviewRequest): String {
        return buildString {
            // Role & mindset
            appendLine("You are a senior software engineer performing a thorough code review.")
            appendLine("Review the diff below for real, meaningful issues. Do NOT flag trivial style nits.")
            appendLine()

            // Review checklist — what to look for
            appendLine("<review-checklist>")
            appendLine("Evaluate the diff against each category. Skip categories that are not relevant.")
            appendLine("- Security: injection, auth bypass, secrets in code, insecure crypto, path traversal")
            appendLine("- Logic: off-by-one, wrong boolean, missing edge cases, race conditions, infinite loops")
            appendLine("- Performance: N+1 queries, unnecessary allocations in hot paths, missing pagination, O(n^2) on large inputs")
            appendLine("- Error handling: swallowed exceptions, missing null checks, unvalidated external input, no retry on transient failures")
            appendLine("- Design: god functions (>40 lines of logic), tight coupling, broken encapsulation, violation of single-responsibility")
            appendLine("- Naming: misleading names, overly generic names (data, info, temp), inconsistency with codebase conventions")
            appendLine("- Null safety: unguarded nullable access, platform types used without checks, force-unwraps")
            appendLine("- Testing: untested public API, missing edge-case tests, brittle assertions, test logic that duplicates production code")
            appendLine("</review-checklist>")
            appendLine()

            // Severity definitions
            appendLine("<severity-definitions>")
            appendLine("Use these severity levels precisely:")
            appendLine("- \"error\" = Critical: security vulnerabilities, data loss, crashes, auth bypass, corruption. Must be fixed before merge.")
            appendLine("- \"warning\" = Major: performance regressions, missing edge cases, maintainability traps, poor error handling. Should be fixed.")
            appendLine("- \"info\" = Minor: naming improvements, small refactors, style suggestions. Nice to fix but non-blocking.")
            appendLine("</severity-definitions>")
            appendLine()

            // Quality guidance
            appendLine("<quality-guidance>")
            appendLine("- Be specific: reference exact variable/function names and explain WHY it is a problem, not just WHAT.")
            appendLine("- Be actionable: include a concrete suggestion or code fix when possible.")
            appendLine("- Avoid false positives: if you are less than 70% confident, do not report it.")
            appendLine("- Focus on the changed lines. Do not review unchanged code unless it is directly affected by the change.")
            appendLine("- Prefer fewer, high-quality findings over many low-confidence ones.")
            appendLine("</quality-guidance>")
            appendLine()

            // Output schema
            appendLine("<output-format>")
            appendLine("Return a JSON array of findings. Each finding is an object with:")
            appendLine("- \"filePath\": string — relative path from the diff header (e.g. \"src/main/Foo.kt\")")
            appendLine("- \"line\": integer — line number in the NEW file")
            appendLine("- \"endLine\": integer or null — end line if the issue spans multiple lines")
            appendLine("- \"severity\": \"error\" | \"warning\" | \"info\"")
            appendLine("- \"ruleId\": string or null — category: \"security\", \"logic\", \"performance\", \"error-handling\", \"design\", \"naming\", \"null-safety\", \"testing\"")
            appendLine("- \"message\": string — concise description of the issue and why it matters")
            appendLine("- \"suggestion\": string or null — what to do instead")
            appendLine("- \"suggestionPatch\": string or null — replacement code for the affected lines (plain code, not a unified diff)")
            appendLine()
            appendLine("Return ONLY the JSON array. No markdown fences, no explanation text.")
            appendLine("If there are no issues, return: []")
            appendLine("</output-format>")
            appendLine()

            appendLine("Project: ${request.projectName}")
            appendLine("Base ref: ${request.baseRef}")
            appendLine()

            // Append custom review instructions if configured
            val customPrompt = ReviewSettings.getInstance().state.customReviewPrompt.trim()
            if (customPrompt.isNotEmpty()) {
                appendLine("<additional-instructions>")
                append(customPrompt)
                append("\n")
                appendLine("</additional-instructions>")
                append("\n")
            }

            appendLine("<diff>")
            appendLine(request.diff)
            appendLine("</diff>")
        }
    }

    /**
     * Parse the claude CLI JSON output into a list of findings.
     *
     * The CLI wraps its output in a JSON envelope with a `result` field
     * when using `--output-format json`.
     */
    private fun parseResponse(stdout: String): List<ReviewFinding> {
        if (stdout.isBlank()) {
            log.warn("Claude CLI returned empty output")
            return emptyList()
        }

        try {
            // Parse the outer CLI envelope to extract the result text
            val envelope = json.parseToJsonElement(stdout).jsonObject
            val resultText = envelope["result"]?.jsonPrimitive?.content ?: stdout

            return parseFindingsFromText(resultText)
        } catch (e: Exception) {
            log.warn("Failed to parse as CLI envelope, trying raw parse: ${e.message}")
            return parseFindingsFromText(stdout)
        }
    }

    private fun parseFindingsFromText(text: String): List<ReviewFinding> {
        // Strip markdown code fences if present
        val cleaned = text
            .replace(Regex("^```(?:json)?\\s*", RegexOption.MULTILINE), "")
            .replace(Regex("^```\\s*$", RegexOption.MULTILINE), "")
            .trim()

        // Try direct parse
        try {
            return json.decodeFromString<List<ReviewFinding>>(cleaned)
        } catch (e: Exception) {
            log.info("Direct parse failed, trying regex extraction: ${e.message}")
        }

        // Fallback: extract JSON array via regex
        val arrayMatch = Regex("\\[\\s*\\{[\\s\\S]*}\\s*]").find(cleaned)
            ?: Regex("\\[\\s*]").find(cleaned)
        if (arrayMatch != null) {
            try {
                return json.decodeFromString<List<ReviewFinding>>(arrayMatch.value)
            } catch (e: Exception) {
                log.warn("Regex-extracted JSON parse also failed: ${e.message}")
            }
        }

        throw ClaudeCliException("Failed to parse Claude response as JSON findings.\nResponse (first 500 chars): ${cleaned.take(500)}")
    }

    /**
     * Resolve the claude CLI path. Uses the configured path if set,
     * otherwise checks common installation locations, then falls back to `which`.
     */
    private fun resolveCliPath(configured: String): String {
        if (configured.isNotBlank()) {
            val file = File(configured)
            if (file.exists() && file.canExecute()) return configured
            throw ClaudeCliException(
                "Configured Claude CLI path does not exist or is not executable: $configured"
            )
        }

        val commonPaths = listOf(
            "/usr/local/bin/claude",
            "/opt/homebrew/bin/claude",
            "${System.getProperty("user.home")}/.local/bin/claude",
            "${System.getProperty("user.home")}/.claude/bin/claude"
        )

        for (path in commonPaths) {
            val file = File(path)
            if (file.exists() && file.canExecute()) {
                log.info("Auto-detected claude CLI at $path")
                return path
            }
        }

        // Try `which`
        try {
            val proc = ProcessBuilder("which", "claude")
                .redirectErrorStream(true)
                .start()
            val output = proc.inputStream.bufferedReader().readText().trim()
            if (proc.waitFor(5, TimeUnit.SECONDS) && proc.exitValue() == 0 && output.isNotBlank()) {
                log.info("Found claude CLI via which: $output")
                return output
            }
        } catch (e: Exception) {
            log.info("'which claude' failed: ${e.message}")
        }

        throw ClaudeCliException(
            "Claude CLI not found. Install it or set the path in Settings > Tools > AI Review."
        )
    }
}

class ClaudeCliException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
