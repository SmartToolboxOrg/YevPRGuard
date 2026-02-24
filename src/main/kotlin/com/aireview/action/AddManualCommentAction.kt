package com.aireview.action

import com.aireview.service.FindingsManager
import com.aireview.ui.AddCommentDialog
import com.aireview.util.PathUtil
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys

/**
 * Action to add a manual review comment on the current editor line.
 * Available in both the editor context menu and the gutter context menu.
 */
class AddManualCommentAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val vFile = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return

        val relativePath = PathUtil.getRelativePath(project, vFile) ?: return
        val caretLine = editor.caretModel.logicalPosition.line + 1 // 1-based

        val dialog = AddCommentDialog(relativePath, caretLine)
        if (dialog.showAndGet()) {
            val message = dialog.getMessage()
            if (message.isNotEmpty()) {
                FindingsManager.getInstance(project).addManualComment(relativePath, caretLine, message)
            }
        }
    }

    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        val vFile = e.getData(CommonDataKeys.VIRTUAL_FILE)
        e.presentation.isEnabledAndVisible = editor != null && vFile != null && e.project != null
    }

    override fun getActionUpdateThread() = com.intellij.openapi.actionSystem.ActionUpdateThread.BGT
}
