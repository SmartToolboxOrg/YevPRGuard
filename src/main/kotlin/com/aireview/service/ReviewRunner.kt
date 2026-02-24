package com.aireview.service

import com.aireview.model.ReviewMode
import com.aireview.model.ReviewRequest
import com.aireview.model.ReviewResult
import com.aireview.settings.ReviewSettings
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project

/**
 * Orchestrates a full review run: collect diff -> check cache -> call service -> store findings.
 *
 * Runs as a cancellable background task via ProgressManager, keeping the EDT free.
 */
class ReviewRunner(private val project: Project) {

    private val log = Logger.getInstance(ReviewRunner::class.java)

    /**
     * Launch a review in a background task.
     *
     * @param baseRef Base git ref to diff against
     * @param headRef Head ref (used only in RANGE mode)
     * @param mode WORKTREE or RANGE
     * @param fileFilter Optional single-file filter (relative path)
     * @param forceRefresh If true, skip cache
     * @param onComplete Callback invoked on EDT after success
     * @param onError Callback invoked on EDT after failure
     */
    fun run(
        baseRef: String,
        headRef: String,
        mode: ReviewMode,
        fileFilter: String? = null,
        forceRefresh: Boolean = false,
        onComplete: ((ReviewResult) -> Unit)? = null,
        onError: ((String) -> Unit)? = null
    ) {
        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project,
            "Running AI Code Review...",
            true  // cancellable
        ) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    indicator.text = "Collecting git diff..."
                    indicator.fraction = 0.1

                    val gitService = GitDiffService(project)
                    val diffResult = gitService.collectDiff(baseRef, headRef, mode, fileFilter)

                    indicator.checkCanceled()

                    if (diffResult.diff.isBlank()) {
                        throw IllegalStateException("No changes found in diff")
                    }

                    // Check cache
                    if (!forceRefresh) {
                        val cached = FindingsManager.getInstance(project).getCachedResult(diffResult.diffHash)
                        if (cached != null) {
                            log.info("Using cached results for diff hash ${diffResult.diffHash.take(8)}")
                            FindingsManager.getInstance(project).setFindings(cached)
                            ApplicationManager.getApplication().invokeLater {
                                onComplete?.invoke(cached)
                            }
                            return
                        }
                    }

                    indicator.text = "Running Claude code review..."
                    indicator.isIndeterminate = true

                    val request = ReviewRequest(
                        projectName = project.name,
                        baseRef = baseRef,
                        headRef = headRef,
                        mode = mode.name,
                        diff = diffResult.diff,
                        files = diffResult.files
                    )

                    val claudeService = ClaudeCodeService()
                    val findings = claudeService.review(request, indicator)

                    indicator.checkCanceled()
                    indicator.text = "Processing ${findings.size} findings..."
                    indicator.fraction = 0.9

                    val result = ReviewResult(
                        findings = findings,
                        diffHash = diffResult.diffHash,
                        baseRef = baseRef,
                        headRef = headRef
                    )

                    FindingsManager.getInstance(project).setFindings(result)

                    if (diffResult.truncated) {
                        notify(
                            "AI Review Complete (Truncated)",
                            "Found ${findings.size} finding(s). Note: diff was truncated due to size limits.",
                            NotificationType.WARNING
                        )
                    }

                    ApplicationManager.getApplication().invokeLater {
                        onComplete?.invoke(result)
                    }

                } catch (e: Exception) {
                    if (e is com.intellij.openapi.progress.ProcessCanceledException) {
                        log.info("Review cancelled by user")
                        return
                    }
                    log.error("Review failed", e)
                    val message = when (e) {
                        is GitCommandException -> e.message ?: "Git command failed"
                        is ClaudeCliException -> e.message ?: "Claude CLI error"
                        else -> "${(e.message ?: "Unknown error").lines().first().take(200)}\n\nSee IDE log for full details."
                    }
                    notify("AI Review Failed", message, NotificationType.ERROR)
                    ApplicationManager.getApplication().invokeLater {
                        onError?.invoke(e.message ?: "Unknown error")
                    }
                }
            }
        })
    }

    private fun notify(title: String, content: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("AI Review")
            .createNotification(title, content, type)
            .notify(project)
    }
}
