package com.aireview.service

import com.aireview.model.ReviewFileInfo
import com.aireview.model.ReviewMode
import com.aireview.settings.ReviewSettings
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Collects git diff and changed-file metadata by shelling out to git.
 *
 * Uses GeneralCommandLine-style process execution for cross-platform safety.
 * All methods are intended to run off the EDT (in a background task).
 */
class GitDiffService(private val project: Project) {

    private val log = Logger.getInstance(GitDiffService::class.java)

    data class DiffResult(
        val diff: String,
        val diffHash: String,
        val files: List<ReviewFileInfo>,
        val truncated: Boolean
    )

    /**
     * Finds the git repository root for the project.
     */
    fun findGitRoot(): File? {
        val basePath = project.basePath ?: return null
        var dir = File(basePath)
        while (dir.parentFile != null) {
            if (File(dir, ".git").exists()) return dir
            dir = dir.parentFile
        }
        return null
    }

    /**
     * Validate a git ref to prevent argument injection.
     * Rejects refs starting with '-' which could be interpreted as git flags.
     */
    private fun validateRef(ref: String) {
        if (ref.startsWith("-")) {
            throw IllegalArgumentException("Invalid git ref: '$ref'. Refs must not start with '-'.")
        }
    }

    /**
     * Check whether a git ref exists in the repository.
     * Returns true if `git rev-parse --verify <ref>` succeeds.
     */
    fun refExists(gitRoot: File, ref: String): Boolean {
        return try {
            val process = ProcessBuilder("git", "rev-parse", "--verify", "--quiet", ref)
                .directory(gitRoot)
                .redirectErrorStream(true)
                .start()
            process.inputStream.readAllBytes() // drain output
            process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0
        } catch (_: Exception) {
            false
        }
    }

    /**
     * List available local and remote branch names for error messages.
     */
    fun listBranches(gitRoot: File): List<String> {
        return try {
            val process = ProcessBuilder("git", "branch", "-a", "--format=%(refname:short)")
                .directory(gitRoot)
                .redirectErrorStream(false)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor(10, TimeUnit.SECONDS)
            output.lines().filter { it.isNotBlank() }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Collect unified diff based on the chosen mode.
     */
    fun collectDiff(
        baseRef: String,
        headRef: String,
        mode: ReviewMode,
        fileFilter: String? = null
    ): DiffResult {
        validateRef(baseRef)
        validateRef(headRef)

        val gitRoot = findGitRoot()
            ?: throw IllegalStateException("No git repository found for project ${project.name}")

        // Validate that the refs actually exist in this repo
        if (!refExists(gitRoot, baseRef)) {
            val branches = listBranches(gitRoot)
            val branchList = if (branches.isNotEmpty()) {
                "\nAvailable branches: ${branches.joinToString(", ")}"
            } else ""
            throw GitCommandException(
                "Git ref '$baseRef' not found. Check the base ref in Settings > Tools > AI Review.$branchList",
                128
            )
        }
        if (mode == ReviewMode.RANGE && !refExists(gitRoot, headRef)) {
            val branches = listBranches(gitRoot)
            val branchList = if (branches.isNotEmpty()) {
                "\nAvailable branches: ${branches.joinToString(", ")}"
            } else ""
            throw GitCommandException(
                "Git ref '$headRef' not found.$branchList",
                128
            )
        }

        val settings = ReviewSettings.getInstance().state

        // Build the git diff command
        val diffArgs = mutableListOf("git", "diff", "--unified=3", "--no-color")

        when (mode) {
            ReviewMode.WORKTREE -> {
                // Compare base branch to working tree (includes staged + unstaged)
                diffArgs.add(baseRef)
            }
            ReviewMode.RANGE -> {
                // Explicit commit range
                diffArgs.add("$baseRef..$headRef")
            }
        }

        if (fileFilter != null) {
            diffArgs.add("--")
            diffArgs.add(fileFilter)
        }

        val rawDiff = runGit(gitRoot, diffArgs)

        // Check size limits — truncate at byte boundary to avoid corrupting multi-byte chars
        val rawBytes = rawDiff.toByteArray(Charsets.UTF_8)
        val truncated = rawBytes.size > settings.maxDiffSizeBytes
        val diff = if (truncated) {
            log.warn("Diff exceeds max size (${settings.maxDiffSizeBytes} bytes), truncating")
            truncateUtf8(rawBytes, settings.maxDiffSizeBytes)
        } else {
            rawDiff
        }

        // Get list of changed files
        val nameArgs = mutableListOf("git", "diff", "--name-only")
        when (mode) {
            ReviewMode.WORKTREE -> nameArgs.add(baseRef)
            ReviewMode.RANGE -> nameArgs.add("$baseRef..$headRef")
        }
        if (fileFilter != null) {
            nameArgs.add("--")
            nameArgs.add(fileFilter)
        }

        val changedFiles = runGit(gitRoot, nameArgs)
            .lines()
            .filter { it.isNotBlank() }

        // Build file info list with optional content
        val files = changedFiles.map { relativePath ->
            val file = File(gitRoot, relativePath)
            val content = if (settings.sendFileContent && file.exists() && file.isFile) {
                val bytes = file.readBytes()
                if (bytes.size <= settings.maxFileContentBytes) {
                    String(bytes, Charsets.UTF_8)
                } else {
                    log.info("File $relativePath exceeds content limit, skipping content")
                    null
                }
            } else null

            ReviewFileInfo(
                path = relativePath,
                content = content,
                language = detectLanguage(relativePath)
            )
        }

        val hash = sha256(diff)

        return DiffResult(
            diff = diff,
            diffHash = hash,
            files = files,
            truncated = truncated
        )
    }

    /**
     * Detect project metadata (build system, primary language).
     */
    fun detectProjectMeta(): Map<String, String> {
        val basePath = project.basePath ?: return emptyMap()
        val meta = mutableMapOf<String, String>()

        val base = File(basePath)
        when {
            File(base, "build.gradle.kts").exists() -> meta["buildSystem"] = "gradle-kts"
            File(base, "build.gradle").exists() -> meta["buildSystem"] = "gradle"
            File(base, "pom.xml").exists() -> meta["buildSystem"] = "maven"
        }

        return meta
    }

    /**
     * Truncate UTF-8 bytes at a safe boundary (not mid-character) and decode back to String.
     */
    private fun truncateUtf8(bytes: ByteArray, maxBytes: Int): String {
        var end = minOf(bytes.size, maxBytes)
        // Walk backward to find a valid UTF-8 character boundary
        while (end > 0 && (bytes[end - 1].toInt() and 0xC0) == 0x80) {
            end--
        }
        // Drop the incomplete lead byte if we're at one
        if (end > 0 && end < maxBytes) {
            val leadByte = bytes[end - 1].toInt() and 0xFF
            val expectedLen = when {
                leadByte < 0x80 -> 1
                leadByte in 0xC0..0xDF -> 2
                leadByte in 0xE0..0xEF -> 3
                leadByte in 0xF0..0xF7 -> 4
                else -> 1
            }
            val availableBytes = maxBytes - (end - 1)
            if (availableBytes < expectedLen) {
                end-- // drop the incomplete multi-byte sequence lead
            }
        }
        return String(bytes, 0, end, Charsets.UTF_8)
    }

    private fun runGit(workDir: File, args: List<String>): String {
        log.info("Running: ${args.joinToString(" ")} in $workDir")

        val process = ProcessBuilder(args)
            .directory(workDir)
            .redirectErrorStream(false)
            .start()

        // Read stderr in a separate thread to prevent deadlock when buffer fills
        val stderrFuture = CompletableFuture.supplyAsync {
            process.errorStream.bufferedReader().readText()
        }

        val stdout = process.inputStream.bufferedReader().readText()

        val exited = process.waitFor(60, TimeUnit.SECONDS)
        if (!exited) {
            process.destroyForcibly()
            throw GitCommandException("Git command timed out after 60 seconds: ${args.joinToString(" ")}", -1)
        }

        val exitCode = process.exitValue()
        val stderr = stderrFuture.get(5, TimeUnit.SECONDS)

        if (exitCode != 0) {
            log.warn("Git command failed (exit $exitCode): $stderr")
            throw GitCommandException("Git command failed: ${args.joinToString(" ")}\n$stderr", exitCode)
        }

        return stdout
    }

    private fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(input.toByteArray(Charsets.UTF_8))
        return hashBytes.joinToString("") { "%02x".format(it) }
    }

    companion object {
        fun detectLanguage(filePath: String): String? {
            val ext = filePath.substringAfterLast('.', "").lowercase()
            return when (ext) {
                "java" -> "JAVA"
                "kt", "kts" -> "KOTLIN"
                "ts", "tsx" -> "TYPESCRIPT"
                "js", "jsx" -> "JAVASCRIPT"
                "py" -> "PYTHON"
                "go" -> "GO"
                "rs" -> "RUST"
                "rb" -> "RUBY"
                "scala" -> "SCALA"
                "swift" -> "SWIFT"
                "cpp", "cc", "cxx", "c" -> "C_CPP"
                "cs" -> "CSHARP"
                "xml" -> "XML"
                "yaml", "yml" -> "YAML"
                "json" -> "JSON"
                "sql" -> "SQL"
                "sh", "bash" -> "SHELL"
                "md" -> "MARKDOWN"
                else -> null
            }
        }
    }
}

class GitCommandException(message: String, val exitCode: Int) : RuntimeException(message)
