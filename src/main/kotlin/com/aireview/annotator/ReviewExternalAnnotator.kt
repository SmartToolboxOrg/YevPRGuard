package com.aireview.annotator

import com.aireview.model.ReviewFinding
import com.aireview.model.Severity
import com.aireview.quickfix.ApplySuggestionQuickFix
import com.aireview.service.FindingsManager
import com.aireview.util.PathUtil
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.ExternalAnnotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile

/**
 * External annotator that adds inline highlights and tooltips for review findings.
 *
 * ExternalAnnotator is used (rather than a regular Annotator) because:
 * 1. It decouples the "info collection" phase from annotation, avoiding EDT work.
 * 2. It is appropriate for results that come from external analysis (our review service).
 *
 * The flow is:
 * - collectInformation(): gathers file identity (runs on EDT, must be fast)
 * - doAnnotate(): finds findings for this file (runs on background thread)
 * - apply(): creates annotations (runs on EDT)
 */
class ReviewExternalAnnotator : ExternalAnnotator<ReviewExternalAnnotator.Info, List<ReviewFinding>>() {

    data class Info(
        val project: Project,
        val relativePath: String?,
        val vFile: VirtualFile?
    )

    override fun collectInformation(file: PsiFile, editor: Editor, hasErrors: Boolean): Info {
        val project = file.project
        val vFile = file.virtualFile
        val relativePath = getRelativePath(project, vFile)
        return Info(project, relativePath, vFile)
    }

    override fun doAnnotate(info: Info): List<ReviewFinding> {
        val path = info.relativePath ?: return emptyList()
        return FindingsManager.getInstance(info.project).getFindingsForFile(path)
    }

    override fun apply(file: PsiFile, findings: List<ReviewFinding>, holder: AnnotationHolder) {
        if (findings.isEmpty()) return
        val document = file.viewProvider.document ?: return

        for (finding in findings) {
            val lineIndex = finding.line - 1 // Convert to 0-based
            if (lineIndex < 0 || lineIndex >= document.lineCount) continue

            val startOffset = document.getLineStartOffset(lineIndex)
            val endLineIndex = (finding.endLine ?: finding.line) - 1
            val safeEndLine = endLineIndex.coerceIn(0, document.lineCount - 1)
            val endOffset = document.getLineEndOffset(safeEndLine)

            if (startOffset >= endOffset) continue

            val severity = when (finding.severityEnum) {
                Severity.ERROR -> HighlightSeverity.ERROR
                Severity.WARNING -> HighlightSeverity.WARNING
                Severity.INFO -> HighlightSeverity.WEAK_WARNING
            }

            val tooltip = buildString {
                val ruleTag = finding.ruleId?.let { " [$it]" } ?: ""
                append("AI Review$ruleTag: ${finding.message}")
                if (finding.suggestion != null) {
                    append("\n\nSuggestion: ${finding.suggestion}")
                }
            }

            val builder = holder.newAnnotation(severity, "AI Review: ${finding.message}")
                .range(TextRange(startOffset, endOffset))
                .tooltip(tooltip)
                .needsUpdateOnTyping(false)

            // Attach quick-fix if a suggestion patch exists
            if (finding.suggestionPatch != null) {
                builder.withFix(ApplySuggestionQuickFix(finding))
            }

            builder.create()
        }
    }

    private fun getRelativePath(project: Project, vFile: VirtualFile?): String? =
        PathUtil.getRelativePath(project, vFile)
}
