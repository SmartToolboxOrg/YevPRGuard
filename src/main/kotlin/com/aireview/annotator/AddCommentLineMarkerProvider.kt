package com.aireview.annotator

import com.aireview.service.FindingsManager
import com.aireview.ui.AddCommentDialog
import com.aireview.util.PathUtil
import com.intellij.codeInsight.daemon.GutterIconNavigationHandler
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.ui.JBColor
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseEvent
import javax.swing.Icon

/**
 * Gutter "+" icon on lines without existing findings.
 * Clicking opens the AddCommentDialog to add a manual review comment.
 */
class AddCommentLineMarkerProvider : LineMarkerProvider {

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        // Only process leaf elements
        if (element.firstChild != null) return null

        val project = element.project
        val psiFile = element.containingFile ?: return null
        val vFile = psiFile.virtualFile ?: return null

        val relativePath = PathUtil.getRelativePath(project, vFile) ?: return null

        // Only show if there are any findings at all (review has been run)
        val manager = FindingsManager.getInstance(project)
        if (manager.getSelectableFindings().isEmpty()) return null

        val document = psiFile.viewProvider.document ?: return null
        val offset = element.textRange.startOffset
        val lineNumber = document.getLineNumber(offset) + 1 // 1-based

        // Skip lines that already have findings (those show severity icons)
        val lineFindings = manager.getFindingsForFile(relativePath)
            .filter { lineNumber in it.lineRange }
        if (lineFindings.isNotEmpty()) return null

        // Only attach to the first element on this line
        val lineStartOffset = document.getLineStartOffset(lineNumber - 1)
        if (offset != findFirstLeafOffset(psiFile, lineStartOffset)) return null

        val handler = GutterIconNavigationHandler<PsiElement> { _: MouseEvent, elt: PsiElement ->
            val path = PathUtil.getRelativePath(elt.project, elt.containingFile?.virtualFile) ?: return@GutterIconNavigationHandler
            val doc = elt.containingFile?.viewProvider?.document ?: return@GutterIconNavigationHandler
            val line = doc.getLineNumber(elt.textRange.startOffset) + 1

            val dialog = AddCommentDialog(path, line)
            if (dialog.showAndGet()) {
                val message = dialog.getMessage()
                if (message.isNotEmpty()) {
                    FindingsManager.getInstance(elt.project).addManualComment(path, line, message)
                }
            }
        }

        return LineMarkerInfo(
            element,
            element.textRange,
            AddCommentIcon(),
            { "Add review comment" },
            handler,
            GutterIconRenderer.Alignment.RIGHT
        ) { "Add review comment" }
    }

    private fun findFirstLeafOffset(file: PsiFile, startOffset: Int): Int {
        var el = file.findElementAt(startOffset)
        while (el != null && el.textRange.startOffset < startOffset) {
            el = el.nextSibling
        }
        return el?.textRange?.startOffset ?: startOffset
    }

    /**
     * Small gray "+" icon for the gutter.
     */
    private class AddCommentIcon : Icon {
        override fun getIconWidth(): Int = 12
        override fun getIconHeight(): Int = 12

        override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = JBColor(0xA0A0A0, 0x6E6E6E)
            g2.stroke = java.awt.BasicStroke(1.5f)
            // Horizontal line of "+"
            g2.drawLine(x + 3, y + 6, x + 9, y + 6)
            // Vertical line of "+"
            g2.drawLine(x + 6, y + 3, x + 6, y + 9)
            g2.dispose()
        }
    }
}
