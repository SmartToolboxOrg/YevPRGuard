package com.aireview.action

import com.aireview.model.ReviewMode
import com.aireview.service.ReviewRunner
import com.aireview.settings.ReviewSettings
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.wm.ToolWindowManager

/**
 * Action registered under Tools > AI Review > Run Review.
 *
 * Triggers a full review using the default settings and opens/focuses the
 * AI Review tool window to show results.
 */
class RunReviewAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val settings = ReviewSettings.getInstance().state
        val mode = try {
            ReviewMode.valueOf(settings.defaultMode)
        } catch (_: IllegalArgumentException) {
            ReviewMode.WORKTREE
        }

        // Open/focus the tool window
        ToolWindowManager.getInstance(project)
            .getToolWindow("AI Review")
            ?.show()

        ReviewRunner(project).run(
            baseRef = settings.defaultBaseRef,
            headRef = "HEAD",
            mode = mode
        )
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }
}
