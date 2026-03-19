package com.aireview.action

import com.aireview.service.ClaudeCliException
import com.aireview.service.ClaudeCodeService
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Generates a short, clean commit message from the current staged (or working-tree) diff
 * using Claude CLI and copies it to the system clipboard.
 */
class GenerateCommitMessageAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        generateCommitMessage(project)
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    companion object {

        fun generateCommitMessage(project: Project) {
            val basePath = project.basePath ?: return

            ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Generating commit message...", true) {
                override fun run(indicator: ProgressIndicator) {
                    try {
                        indicator.text = "Collecting diff..."
                        indicator.fraction = 0.2

                        val diff = collectDiff(File(basePath))
                        if (diff.isBlank()) {
                            notify(project, "No changes found. Stage or modify files first.", NotificationType.WARNING)
                            return
                        }

                        indicator.text = "Asking Claude for commit message..."
                        indicator.fraction = 0.5

                        val result = ClaudeCodeService().generateCommitMessage(diff, indicator)

                        indicator.fraction = 1.0

                        // Copy to clipboard on EDT
                        ApplicationManager.getApplication().invokeLater {
                            val selection = StringSelection(result.message)
                            Toolkit.getDefaultToolkit().systemClipboard.setContents(selection, selection)
                        }

                        val safeMessage = StringUtil.escapeXmlEntities(result.message)
                        notify(project, "Commit message copied to clipboard:<br><code>$safeMessage</code>", NotificationType.INFORMATION)

                    } catch (ex: ClaudeCliException) {
                        notify(project, ex.message ?: "Failed to generate commit message", NotificationType.ERROR)
                    } catch (ex: Exception) {
                        notify(project, "Unexpected error: ${ex.message}", NotificationType.ERROR)
                    }
                }
            })
        }

        /**
         * Collect diff for commit message generation.
         * Uses staged changes first; falls back to unstaged working-tree diff if nothing is staged.
         * Truncates to maxDiffSizeBytes to stay consistent with the rest of the plugin.
         */
        private fun collectDiff(workDir: File): String {
            val maxBytes = com.aireview.settings.ReviewSettings.getInstance().state.maxDiffSizeBytes
            val staged = runGit(workDir, listOf("git", "diff", "--cached", "--unified=3", "--no-color"))
            val diff = if (staged.isNotBlank()) staged
                       else runGit(workDir, listOf("git", "diff", "HEAD", "--unified=3", "--no-color"))
            return if (diff.length > maxBytes) diff.take(maxBytes) + "\n... (diff truncated)" else diff
        }

        /**
         * Run a git command and return stdout. Merges stderr into stdout to prevent
         * pipe-buffer deadlock, and throws if the process times out.
         */
        private fun runGit(workDir: File, args: List<String>): String {
            val process = ProcessBuilder(args)
                .directory(workDir)
                .redirectErrorStream(true)  // merge stderr → stdout to prevent deadlock
                .start()
            val output = process.inputStream.bufferedReader().readText()
            val finished = process.waitFor(30, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                throw RuntimeException("git timed out: ${args.joinToString(" ")}")
            }
            return if (process.exitValue() == 0) output else ""
        }

        private fun notify(project: Project, message: String, type: NotificationType) {
            ApplicationManager.getApplication().invokeLater {
                NotificationGroupManager.getInstance()
                    .getNotificationGroup("AI Review")
                    .createNotification(message, type)
                    .notify(project)
            }
        }
    }
}
