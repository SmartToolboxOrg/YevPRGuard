package com.aireview.action

import com.aireview.model.ReviewMode
import com.aireview.service.ReviewRunner
import com.aireview.settings.ReviewSettings
import com.aireview.util.PathUtil
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.wm.ToolWindowManager

/**
 * Context action that runs AI review scoped to the currently open file.
 *
 * Available in the editor context menu and under Tools > AI Review.
 * Computes the file's path relative to the project root and passes it
 * as a filter to the review runner.
 */
class RunReviewCurrentFileAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val vFile = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return

        val relativePath = PathUtil.getRelativePath(project, vFile) ?: return
        val settings = ReviewSettings.getInstance().state
        val mode = try {
            ReviewMode.valueOf(settings.defaultMode)
        } catch (_: IllegalArgumentException) {
            ReviewMode.WORKTREE
        }

        ToolWindowManager.getInstance(project)
            .getToolWindow("AI Review")
            ?.show()

        ReviewRunner(project).run(
            baseRef = settings.defaultBaseRef,
            headRef = "HEAD",
            mode = mode,
            fileFilter = relativePath
        )
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible =
            e.project != null && e.getData(CommonDataKeys.VIRTUAL_FILE) != null
    }
}
