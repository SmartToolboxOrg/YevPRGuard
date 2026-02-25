package com.aireview.action

import com.aireview.model.ReviewMode
import com.aireview.model.ReviewRequest
import com.aireview.model.ReviewResult
import com.aireview.service.*
import com.aireview.settings.ReviewSettings
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import java.io.File

/**
 * One-click action that orchestrates the full review-and-publish pipeline:
 * collect diff → AI review → generate PR description → push branch → create/detect PR → update PR → publish comments.
 */
class OneClickReviewAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        runFullPipeline(project)
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    companion object {
        private val log = Logger.getInstance(OneClickReviewAction::class.java)

        /**
         * Run the full review-and-publish pipeline. Can be called from the action or tool window button.
         */
        fun runFullPipeline(project: Project) {
            val basePath = project.basePath ?: return

            ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Review & Create PR", true) {
                override fun run(indicator: ProgressIndicator) {
                    try {
                        val settings = ReviewSettings.getInstance().state
                        val baseRef = settings.defaultBaseRef
                        val ghService = GitHubService(File(basePath))

                        // Step 1: Collect diff
                        indicator.text = "Collecting diff..."
                        indicator.fraction = 0.05
                        indicator.checkCanceled()

                        val diffService = GitDiffService(project)
                        val diffResult = diffService.collectDiff(baseRef, "HEAD", ReviewMode.WORKTREE)

                        if (diffResult.diff.isBlank()) {
                            notify(project, "No changes found to review. Make some changes first.", NotificationType.WARNING)
                            return
                        }

                        // Step 2: Run AI review
                        indicator.text = "Running AI code review..."
                        indicator.fraction = 0.10
                        indicator.checkCanceled()

                        val claudeService = ClaudeCodeService()
                        val request = ReviewRequest(
                            projectName = project.name,
                            baseRef = baseRef,
                            headRef = "HEAD",
                            mode = ReviewMode.WORKTREE.name,
                            diff = diffResult.diff,
                            files = diffResult.files
                        )
                        val findings = claudeService.review(request, indicator)

                        // Step 3: Store findings
                        indicator.text = "Storing findings..."
                        indicator.fraction = 0.40
                        indicator.checkCanceled()

                        val reviewResult = ReviewResult(
                            findings = findings,
                            diffHash = diffResult.diffHash,
                            baseRef = baseRef,
                            headRef = "HEAD"
                        )
                        FindingsManager.getInstance(project).setFindings(reviewResult)

                        // Step 4: Generate PR description (incorporating findings)
                        indicator.text = "Generating PR title & description..."
                        indicator.fraction = 0.45
                        indicator.checkCanceled()

                        val currentBranch = ghService.getCurrentBranch()
                        val prDescription = claudeService.generatePrDescription(
                            diffResult.diff, findings, currentBranch, indicator
                        )

                        // Step 5: Push branch if needed
                        indicator.text = "Checking remote branch..."
                        indicator.fraction = 0.65
                        indicator.checkCanceled()

                        if (!ghService.isBranchPushed(currentBranch)) {
                            indicator.text = "Pushing branch to origin..."
                            ghService.pushBranch(currentBranch)
                        }

                        // Step 6: Create or detect PR
                        indicator.text = "Detecting existing PR..."
                        indicator.fraction = 0.70
                        indicator.checkCanceled()

                        var pr = ghService.detectCurrentPr()
                        val prCreated: Boolean

                        if (pr == null) {
                            indicator.text = "Creating PR..."
                            pr = ghService.createPr(prDescription.title, prDescription.description, baseRef)
                            prCreated = true
                        } else {
                            prCreated = false
                        }

                        // Step 7: Update PR title & body
                        indicator.text = "Updating PR title & description..."
                        indicator.fraction = 0.80
                        indicator.checkCanceled()

                        if (!prCreated) {
                            // PR already existed — update its title and body
                            ghService.updatePrTitleAndBody(pr.number, prDescription.title, prDescription.description)
                        }
                        // If we just created it, title/body are already set from createPr()

                        // Step 8: Publish all findings as review comments
                        indicator.text = "Publishing review comments..."
                        indicator.fraction = 0.85
                        indicator.checkCanceled()

                        val repoSlug = ghService.getRepoSlug()
                            ?: throw GitHubCliException("Could not determine repository slug")

                        // Get all findings for publishing (without mutating user selection state)
                        val allFindings = FindingsManager.getInstance(project).getSelectableFindings()

                        val publishResult = if (allFindings.isNotEmpty()) {
                            ghService.publishReview(pr.number, repoSlug, allFindings)
                        } else {
                            GitHubService.PublishResult(published = 0, skipped = 0)
                        }

                        // Done
                        indicator.fraction = 1.0

                        val message = buildString {
                            if (prCreated) {
                                append("Created PR #${pr.number}")
                            } else {
                                append("Updated PR #${pr.number}")
                            }
                            append(" with ${findings.size} finding(s)")
                            if (publishResult.published > 0) {
                                append(", ${publishResult.published} comment(s) published")
                            }
                            if (publishResult.skipped > 0) {
                                append(", ${publishResult.skipped} duplicate(s) skipped")
                            }
                        }
                        notify(project, message, NotificationType.INFORMATION)

                    } catch (ex: GitHubCliException) {
                        notify(project, "GitHub error: ${ex.message}", NotificationType.ERROR)
                    } catch (ex: ClaudeCliException) {
                        notify(project, "Claude CLI error: ${ex.message}", NotificationType.ERROR)
                    } catch (ex: GitCommandException) {
                        notify(project, "Git error: ${ex.message}", NotificationType.ERROR)
                    } catch (ex: com.intellij.openapi.progress.ProcessCanceledException) {
                        notify(project, "Review & publish cancelled.", NotificationType.WARNING)
                    } catch (ex: Exception) {
                        log.error("One-click review failed", ex)
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
