package com.aireview.service

import com.aireview.model.SelectableFinding
import com.aireview.settings.ReviewSettings
import com.intellij.openapi.diagnostic.Logger
import kotlinx.serialization.json.*
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * GitHub CLI (`gh`) wrapper for publishing review comments to a PR.
 */
class GitHubService(private val workDir: File) {

    private val log = Logger.getInstance(GitHubService::class.java)

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    data class PrInfo(
        val number: Int,
        val title: String,
        val headBranch: String,
        val baseBranch: String,
        val body: String = ""
    )

    /**
     * Resolve the gh CLI path from configured setting, common locations, or `which`.
     */
    fun resolveGhCliPath(configured: String = ReviewSettings.getInstance().state.ghCliPath): String {
        if (configured.isNotBlank()) {
            val file = File(configured)
            if (file.exists() && file.canExecute()) return configured
            throw GitHubCliException(
                "Configured gh CLI path does not exist or is not executable: $configured"
            )
        }

        val commonPaths = listOf(
            "/usr/local/bin/gh",
            "/opt/homebrew/bin/gh",
            "${System.getProperty("user.home")}/.local/bin/gh"
        )

        for (path in commonPaths) {
            val file = File(path)
            if (file.exists() && file.canExecute()) {
                log.info("Auto-detected gh CLI at $path")
                return path
            }
        }

        try {
            val proc = ProcessBuilder("which", "gh")
                .redirectErrorStream(true)
                .start()
            val output = proc.inputStream.bufferedReader().readText().trim()
            if (proc.waitFor(5, TimeUnit.SECONDS) && proc.exitValue() == 0 && output.isNotBlank()) {
                log.info("Found gh CLI via which: $output")
                return output
            }
        } catch (e: Exception) {
            log.info("'which gh' failed: ${e.message}")
        }

        throw GitHubCliException(
            "GitHub CLI (gh) not found. Install it from https://cli.github.com/ or set the path in Settings > Tools > AI Review."
        )
    }

    /**
     * Detect the current PR for the checked-out branch.
     */
    fun detectCurrentPr(): PrInfo? {
        val ghPath = resolveGhCliPath()
        val (exitCode, stdout, stderr) = runGh(
            ghPath, "pr", "view", "--json", "number,title,headRefName,baseRefName,body"
        )

        if (exitCode != 0) {
            val combined = "$stdout $stderr"
            if (combined.contains("auth login", ignoreCase = true) ||
                combined.contains("GH_TOKEN", ignoreCase = true) ||
                combined.contains("not logged", ignoreCase = true) ||
                combined.contains("authenticate", ignoreCase = true)) {
                throw GitHubCliException(
                    "GitHub CLI is not authenticated. Set your GitHub Token in Settings > Tools > AI Review, or run 'gh auth login' in a terminal."
                )
            }
            if (combined.contains("no pull requests found", ignoreCase = true) ||
                combined.contains("Could not resolve", ignoreCase = true)) {
                return null
            }
            log.warn("gh pr view failed (exit $exitCode): stdout=${stdout.take(200)} stderr=${stderr.take(200)}")
            throw GitHubCliException("gh pr view failed (exit $exitCode): ${stderr.ifBlank { stdout }.take(300)}")
        }

        return try {
            val obj = json.parseToJsonElement(stdout).jsonObject
            PrInfo(
                number = obj["number"]?.jsonPrimitive?.content?.toIntOrNull() ?: return null,
                title = obj["title"]?.jsonPrimitive?.content ?: "",
                headBranch = obj["headRefName"]?.jsonPrimitive?.content ?: "",
                baseBranch = obj["baseRefName"]?.jsonPrimitive?.content ?: "",
                body = obj["body"]?.jsonPrimitive?.content ?: ""
            )
        } catch (e: Exception) {
            log.warn("Failed to parse PR info: ${e.message}")
            null
        }
    }

    /**
     * Get the repo slug (owner/repo) for the current repository.
     */
    fun getRepoSlug(): String? {
        val ghPath = resolveGhCliPath()
        val (exitCode, stdout, _) = runGh(ghPath, "repo", "view", "--json", "nameWithOwner")

        if (exitCode != 0) return null

        return try {
            val obj = json.parseToJsonElement(stdout).jsonObject
            obj["nameWithOwner"]?.jsonPrimitive?.content
        } catch (e: Exception) {
            log.warn("Failed to parse repo slug: ${e.message}")
            null
        }
    }

    /**
     * Get the current git branch name.
     * Throws if in detached HEAD state.
     */
    fun getCurrentBranch(): String {
        val pb = ProcessBuilder("git", "rev-parse", "--abbrev-ref", "HEAD")
            .directory(workDir)
            .redirectErrorStream(true)
        val process = pb.start()
        val output = process.inputStream.bufferedReader().readText().trim()
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw GitHubCliException("git rev-parse timed out")
        }
        if (process.exitValue() != 0) {
            throw GitHubCliException("Failed to get current branch: $output")
        }
        if (output == "HEAD") {
            throw GitHubCliException("Cannot create PR from detached HEAD state. Please checkout a branch first.")
        }
        return output
    }

    /**
     * Check if the current branch has been pushed to the remote.
     */
    fun isBranchPushed(branch: String): Boolean {
        require(!branch.startsWith("-")) { "Invalid branch name: $branch" }
        val pb = ProcessBuilder("git", "rev-parse", "--verify", "origin/$branch")
            .directory(workDir)
            .redirectErrorStream(true)
        val process = pb.start()
        process.inputStream.bufferedReader().readText() // consume output
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return false
        }
        return process.exitValue() == 0
    }

    /**
     * Push a branch to the remote with upstream tracking.
     */
    fun pushBranch(branch: String) {
        require(!branch.startsWith("-")) { "Invalid branch name: $branch" }
        val pb = ProcessBuilder("git", "push", "-u", "origin", branch)
            .directory(workDir)
            .redirectErrorStream(false)
        val process = pb.start()
        val stderr = CompletableFuture.supplyAsync { process.errorStream.bufferedReader().readText() }
        val stdout = process.inputStream.bufferedReader().readText()
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroy()
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly()
            }
            throw GitHubCliException("git push timed out after 60s")
        }
        val stderrText = stderr.get(5, TimeUnit.SECONDS)
        if (process.exitValue() != 0) {
            throw GitHubCliException("Failed to push branch '$branch': ${stderrText.ifBlank { stdout }}")
        }
        log.info("Pushed branch '$branch' to origin")
    }

    /**
     * Create a new PR via `gh pr create`, then fetch its details via `gh pr view`.
     */
    fun createPr(title: String, body: String, baseBranch: String): PrInfo {
        val ghPath = resolveGhCliPath()
        val (exitCode, stdout, stderr) = runGh(
            ghPath, "pr", "create",
            "--title", title,
            "--body", body,
            "--base", baseBranch
        )

        if (exitCode != 0) {
            throw GitHubCliException("Failed to create PR: ${stderr.ifBlank { stdout }.take(300)}")
        }

        log.info("PR created: ${stdout.trim()}")

        // gh pr create outputs the PR URL; fetch structured info via gh pr view
        val pr = detectCurrentPr()
            ?: throw GitHubCliException("PR was created but could not be detected afterwards")

        return pr
    }

    /**
     * Update a PR's title and body via `gh pr edit`.
     */
    fun updatePrTitleAndBody(prNumber: Int, title: String, body: String) {
        val ghPath = resolveGhCliPath()
        val (exitCode, _, stderr) = runGh(
            ghPath, "pr", "edit", prNumber.toString(),
            "--title", title, "--body", body
        )

        if (exitCode != 0) {
            throw GitHubCliException("Failed to update PR #$prNumber title/body:\n$stderr")
        }

        log.info("Updated PR #$prNumber title and body")
    }

    /**
     * Fetch existing review comments on a PR.
     * Returns a set of (path, line, body) triples for deduplication.
     */
    fun fetchExistingComments(prNumber: Int, repoSlug: String): Set<Triple<String, Int, String>> {
        val ghPath = resolveGhCliPath()
        val existing = mutableSetOf<Triple<String, Int, String>>()
        var page = 1

        while (true) {
            val (exitCode, stdout, _) = runGh(
                ghPath, "api",
                "repos/$repoSlug/pulls/$prNumber/comments",
                "--method", "GET",
                "-f", "per_page=100",
                "-f", "page=$page"
            )

            if (exitCode != 0) {
                log.warn("Failed to fetch existing PR comments (page $page), skipping dedup")
                break
            }

            try {
                val arr = json.parseToJsonElement(stdout).jsonArray
                if (arr.isEmpty()) break

                for (elem in arr) {
                    val obj = elem.jsonObject
                    val path = obj["path"]?.jsonPrimitive?.content ?: continue
                    val line = obj["line"]?.jsonPrimitive?.content?.toIntOrNull()
                        ?: obj["original_line"]?.jsonPrimitive?.content?.toIntOrNull()
                        ?: continue
                    val body = obj["body"]?.jsonPrimitive?.content ?: continue
                    existing.add(Triple(path, line, body))
                }

                if (arr.size < 100) break
                page++
            } catch (e: Exception) {
                log.warn("Failed to parse existing PR comments: ${e.message}")
                break
            }
        }

        return existing
    }

    /**
     * Publish a review with comments to a GitHub PR.
     * Uses kotlinx.serialization for safe JSON payload construction.
     *
     * Builds a structured review body that groups findings by severity,
     * and adds severity badges to individual line comments.
     * Skips comments that already exist on the PR to avoid duplicates.
     */
    fun publishReview(
        prNumber: Int,
        repoSlug: String,
        findings: List<SelectableFinding>,
        reviewBody: String = ""
    ): PublishResult {
        val ghPath = resolveGhCliPath()

        // Fetch existing comments for deduplication
        val existing = fetchExistingComments(prNumber, repoSlug)

        // Filter out duplicates
        val newFindings = findings.filter { sf ->
            val body = buildCommentBody(sf)
            !existing.contains(Triple(sf.finding.filePath, sf.finding.line ?: 0, body))
        }

        val skipped = findings.size - newFindings.size
        if (skipped > 0) {
            log.info("Skipping $skipped duplicate comment(s) already on PR #$prNumber")
        }

        if (newFindings.isEmpty()) {
            log.info("All comments already exist on PR #$prNumber, nothing to publish")
            return PublishResult(published = 0, skipped = skipped)
        }

        val finalBody = reviewBody

        val payload = buildJsonObject {
            put("body", finalBody)
            put("event", "COMMENT")
            putJsonArray("comments") {
                for (sf in newFindings) {
                    val finding = sf.finding
                    val body = buildCommentBody(sf)
                    addJsonObject {
                        put("path", finding.filePath)
                        put("line", finding.line ?: 1)
                        put("side", "RIGHT")
                        put("body", body)
                    }
                }
            }
        }.toString()

        val (exitCode, _, stderr) = runGh(
            ghPath, "api",
            "repos/$repoSlug/pulls/$prNumber/reviews",
            "--method", "POST",
            "--input", "-",
            stdin = payload
        )

        if (exitCode != 0) {
            throw GitHubCliException("Failed to publish review to PR #$prNumber:\n$stderr")
        }

        log.info("Published ${newFindings.size} comments to PR #$prNumber (skipped $skipped duplicates)")
        return PublishResult(published = newFindings.size, skipped = skipped)
    }

    data class PublishResult(val published: Int, val skipped: Int)

    internal companion object {
        /**
         * Build the body for an individual line comment, with a severity badge.
         */
        fun buildCommentBody(sf: SelectableFinding): String {
            val finding = sf.finding
            return buildString {
                when (finding.severity.lowercase()) {
                    "error" -> append("**[Critical]** ")
                    "warning" -> append("**[Major]** ")
                }
                append(finding.message)
                if (finding.suggestion != null) {
                    append("\n\n**Suggestion:** ${finding.suggestion}")
                }
                if (finding.suggestionPatch != null) {
                    append("\n\n```suggestion\n${finding.suggestionPatch}\n```")
                }
            }
        }
    }

    private data class GhResult(val exitCode: Int, val stdout: String, val stderr: String)

    private fun runGh(vararg args: String, stdin: String? = null): GhResult {
        val pb = ProcessBuilder(args.toList())
            .directory(workDir)
            .redirectErrorStream(false)

        // Pass GH_TOKEN from settings if configured
        val token = ReviewSettings.getInstance().state.ghToken
        if (token.isNotBlank()) {
            pb.environment()["GH_TOKEN"] = token
        }

        val process = pb.start()

        if (stdin != null) {
            process.outputStream.use { os ->
                os.write(stdin.toByteArray(Charsets.UTF_8))
                os.flush()
            }
        }

        val stderrFuture = CompletableFuture.supplyAsync {
            process.errorStream.bufferedReader().readText()
        }

        val stdout = process.inputStream.bufferedReader().readText()

        val exited = process.waitFor(30, TimeUnit.SECONDS)
        if (!exited) {
            process.destroy()
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly()
            }
            throw GitHubCliException("gh CLI timed out after 30s")
        }

        val stderr = stderrFuture.get(5, TimeUnit.SECONDS)
        return GhResult(process.exitValue(), stdout, stderr)
    }
}

class GitHubCliException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
