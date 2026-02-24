package com.aireview.action

import com.aireview.model.SelectableFinding
import com.aireview.service.*
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import java.io.File

/**
 * Action that publishes selected review findings as line comments on the current GitHub PR.
 * Only publishes comments — PR title/description is handled by GeneratePrDescriptionAction.
 */
class PublishToGitHubAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        val manager = FindingsManager.getInstance(project)
        val selected = manager.getSelectedFindings()

        if (selected.isEmpty()) {
            notify(project, "No findings selected. Select findings to publish.", NotificationType.WARNING)
            return
        }

        publishFindings(project, selected)
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun getActionUpdateThread() = com.intellij.openapi.actionSystem.ActionUpdateThread.BGT

    companion object {
        /**
         * Publish a list of findings as line comments to the current GitHub PR.
         * Can be called from the action or from a context menu for individual findings.
         */
        fun publishFindings(project: Project, findings: List<SelectableFinding>) {
            val basePath = project.basePath ?: return

            ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Publishing to GitHub PR...", true) {
                override fun run(indicator: ProgressIndicator) {
                    try {
                        val ghService = GitHubService(File(basePath))

                        indicator.text = "Detecting current PR..."
                        indicator.fraction = 0.1

                        val pr = ghService.detectCurrentPr()
                        if (pr == null) {
                            notify(project, "No PR found for the current branch. Push your branch and create a PR first.", NotificationType.ERROR)
                            return
                        }

                        indicator.text = "Getting repository info..."
                        indicator.fraction = 0.3

                        val repoSlug = ghService.getRepoSlug()
                        if (repoSlug == null) {
                            notify(project, "Could not determine repository. Make sure you're in a GitHub repository.", NotificationType.ERROR)
                            return
                        }

                        // Publish line comments (duplicates are skipped automatically)
                        indicator.text = "Publishing review comments..."
                        indicator.fraction = 0.6

                        val result = ghService.publishReview(pr.number, repoSlug, findings)

                        indicator.fraction = 1.0
                        val message = buildString {
                            if (result.published > 0) {
                                val w = if (result.published == 1) "comment" else "comments"
                                append("Published ${result.published} $w to PR #${pr.number}")
                            }
                            if (result.skipped > 0) {
                                if (isNotEmpty()) append(". ")
                                val w = if (result.skipped == 1) "duplicate" else "duplicates"
                                append("Skipped ${result.skipped} $w already on PR")
                            }
                            if (isEmpty()) append("No comments to publish")
                        }
                        val type = if (result.published > 0) NotificationType.INFORMATION else NotificationType.WARNING
                        notify(project, message, type)

                    } catch (ex: GitHubCliException) {
                        notify(project, ex.message ?: "Failed to publish to GitHub PR", NotificationType.ERROR)
                    } catch (ex: Exception) {
                        notify(project, "Unexpected error: ${ex.message}", NotificationType.ERROR)
                    }
                }
            })
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
