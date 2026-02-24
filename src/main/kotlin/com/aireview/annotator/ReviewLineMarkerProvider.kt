package com.aireview.annotator

import com.aireview.model.ReviewFinding
import com.aireview.model.Severity
import com.aireview.service.FindingsManager
import com.aireview.util.PathUtil
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.ui.JBColor
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.Icon

/**
 * Provides gutter icons on lines that have review findings.
 *
 * LineMarkerProvider is the standard IntelliJ extension point for placing
 * icons in the editor gutter. It operates on PSI elements, so we attach
 * the marker to the first leaf PsiElement on each line that has a finding.
 *
 * This provider is language-agnostic (registered with language="" in plugin.xml).
 */
class ReviewLineMarkerProvider : LineMarkerProvider {

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        // Only process leaf elements to avoid duplicate markers
        if (element.firstChild != null) return null

        val project = element.project
        val psiFile = element.containingFile ?: return null
        val vFile = psiFile.virtualFile ?: return null

        val relativePath = getRelativePath(project, vFile) ?: return null
        val findings = FindingsManager.getInstance(project).getFindingsForFile(relativePath)
        if (findings.isEmpty()) return null

        val document = psiFile.viewProvider.document ?: return null
        val offset = element.textRange.startOffset
        val lineNumber = document.getLineNumber(offset) + 1 // 1-based

        // Find findings for this line
        val lineFindings = findings.filter { lineNumber in it.lineRange }
        if (lineFindings.isEmpty()) return null

        // Only attach to the first element on this line to avoid duplicates
        val lineStartOffset = document.getLineStartOffset(lineNumber - 1)
        if (offset != findFirstLeafOffset(psiFile, lineStartOffset)) return null

        val worstSeverity = lineFindings.maxOfOrNull { it.severityEnum } ?: return null
        val tooltip = buildTooltip(lineFindings)

        return LineMarkerInfo(
            element,
            element.textRange,
            SeverityIcon(worstSeverity),
            { tooltip },
            null,
            GutterIconRenderer.Alignment.LEFT
        ) { "AI Review finding" }
    }

    private fun findFirstLeafOffset(file: PsiFile, startOffset: Int): Int {
        var el = file.findElementAt(startOffset)
        // Walk forward to find the first non-whitespace leaf
        while (el != null && el.textRange.startOffset < startOffset) {
            el = el.nextSibling
        }
        return el?.textRange?.startOffset ?: startOffset
    }

    private fun buildTooltip(findings: List<ReviewFinding>): String {
        return buildString {
            append("<html>")
            for (f in findings) {
                val sevLabel = f.severityEnum.name.lowercase().replaceFirstChar { it.uppercase() }
                val ruleTag = f.ruleId?.let { " <i>[$it]</i>" } ?: ""
                append("<b>$sevLabel</b>$ruleTag: ${escapeHtml(f.message)}<br/>")
                if (f.suggestion != null) {
                    append("<i>Suggestion: ${escapeHtml(f.suggestion)}</i><br/>")
                }
            }
            append("</html>")
        }
    }

    private fun escapeHtml(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun getRelativePath(project: Project, vFile: VirtualFile): String? =
        PathUtil.getRelativePath(project, vFile)

    /**
     * Simple colored circle icon rendered for each severity level.
     */
    private class SeverityIcon(private val severity: Severity) : Icon {
        override fun getIconWidth(): Int = 12
        override fun getIconHeight(): Int = 12

        override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = when (severity) {
                Severity.ERROR -> JBColor.RED
                Severity.WARNING -> JBColor.ORANGE
                Severity.INFO -> JBColor(0x6C707E, 0x9DA0A8)
            }
            g2.fillOval(x + 1, y + 1, 10, 10)
            g2.dispose()
        }
    }
}
