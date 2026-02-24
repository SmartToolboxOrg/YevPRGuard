package com.aireview.action

import com.aireview.model.ReviewMode
import com.aireview.service.*
import com.aireview.settings.ReviewSettings
import com.aireview.ui.PublishPreviewDialog
import com.intellij.ide.util.PropertiesComponent
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
import java.io.File

/**
 * Standalone action that generates a PR title and description from the current diff,
 * shows a preview dialog, and updates the PR on GitHub.
 *
 * Caches the generated description keyed by diff hash — if the diff hasn't changed,
 * the cached version is shown immediately without calling Claude again.
 */
class GeneratePrDescriptionAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        generatePrDescription(project)
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    companion object {
        private const val KEY_DIFF_HASH = "aireview.pr.diffHash"
        private const val KEY_TITLE = "aireview.pr.title"
        private const val KEY_DESCRIPTION = "aireview.pr.description"

        /**
         * Generate (or fetch cached) PR description and update the PR.
         * Can be called from the action or from the tool window button.
         */
        fun generatePrDescription(project: Project) {
            val basePath = project.basePath ?: return

            ProgressManager.getInstance().run(object : Task.Backgroundable(project, "PR Description...", true) {
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

                        indicator.text = "Collecting diff..."
                        indicator.fraction = 0.3

                        val settings = ReviewSettings.getInstance().state
                        val baseRef = settings.defaultBaseRef
                        val diffService = GitDiffService(project)
                        val diffResult = diffService.collectDiff(baseRef, "HEAD", ReviewMode.WORKTREE)

                        // Check cache: skip Claude if diff hasn't changed
                        val props = PropertiesComponent.getInstance(project)
                        val cachedHash = props.getValue(KEY_DIFF_HASH)
                        val title: String
                        val description: String

                        if (cachedHash == diffResult.diffHash) {
                            indicator.text = "Using cached PR description..."
                            indicator.fraction = 0.6
                            title = props.getValue(KEY_TITLE) ?: pr.title
                            description = props.getValue(KEY_DESCRIPTION) ?: ""
                        } else {
                            indicator.text = "Generating PR description..."
                            indicator.fraction = 0.5

                            val generated = ClaudeCodeService().generatePrDescription(
                                diffResult.diff, emptyList(), pr.title, indicator
                            )
                            title = generated.title
                            description = generated.description
                        }

                        // Show preview dialog on EDT
                        var dialogTitle: String? = null
                        var dialogDescription: String? = null

                        ApplicationManager.getApplication().invokeAndWait {
                            val dialog = PublishPreviewDialog(
                                pr.number, 0, title, description,
                                okButtonText = "Update PR"
                            )
                            if (dialog.showAndGet()) {
                                dialogTitle = dialog.getPrTitle()
                                dialogDescription = dialog.getDescription()
                            }
                        }

                        if (dialogTitle == null) return // user cancelled

                        // Persist the (possibly edited) title/description with current diff hash
                        props.setValue(KEY_DIFF_HASH, diffResult.diffHash)
                        props.setValue(KEY_TITLE, dialogTitle)
                        props.setValue(KEY_DESCRIPTION, dialogDescription)

                        indicator.text = "Updating PR title and description..."
                        indicator.fraction = 0.8

                        ghService.updatePrTitleAndBody(pr.number, dialogTitle!!, dialogDescription!!)

                        indicator.fraction = 1.0
                        notify(project, "Updated PR #${pr.number} title and description", NotificationType.INFORMATION)

                    } catch (ex: GitHubCliException) {
                        notify(project, ex.message ?: "Failed to update PR", NotificationType.ERROR)
                    } catch (ex: ClaudeCliException) {
                        notify(project, ex.message ?: "Failed to generate PR description", NotificationType.ERROR)
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
