package com.aireview.quickfix

import com.aireview.model.ReviewFinding
import com.aireview.util.PathUtil
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiFile
import java.io.File

/**
 * Intention action that applies a suggestion patch from a review finding.
 *
 * Registered via the ExternalAnnotator's withFix(), which expects IntentionAction.
 * When the user invokes it (Alt+Enter), it attempts to apply the unified diff patch.
 * If application fails, it shows a diff preview dialog for manual review.
 */
class ApplySuggestionQuickFix(
    private val finding: ReviewFinding
) : IntentionAction {

    private val log = Logger.getInstance(ApplySuggestionQuickFix::class.java)

    override fun getText(): String = "Apply AI suggestion"

    override fun getFamilyName(): String = "AI Review"

    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean =
        finding.suggestionPatch != null

    override fun startInWriteAction(): Boolean = false

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        val patch = finding.suggestionPatch ?: return

        val basePath = project.basePath ?: run {
            Messages.showErrorDialog(project, "Cannot determine project base path.", "Apply Suggestion")
            return
        }

        val targetFile = PathUtil.resolveAndValidate(basePath, finding.filePath)
        if (targetFile == null) {
            Messages.showErrorDialog(
                project,
                "Invalid file path: ${finding.filePath}",
                "Apply Suggestion"
            )
            return
        }
        if (!targetFile.exists()) {
            Messages.showErrorDialog(
                project,
                "File not found: ${finding.filePath}",
                "Apply Suggestion"
            )
            return
        }

        val vFile = LocalFileSystem.getInstance().findFileByIoFile(targetFile)
        if (vFile == null) {
            Messages.showErrorDialog(project, "Cannot access file in VFS.", "Apply Suggestion")
            return
        }

        val document = FileDocumentManager.getInstance().getDocument(vFile)
        if (document == null) {
            Messages.showErrorDialog(project, "Cannot open document.", "Apply Suggestion")
            return
        }

        // Always show diff preview for user confirmation before applying
        showDiffPreview(project, document, patch, targetFile)
    }

    /**
     * Attempt a simple patch application by parsing unified diff hunks.
     *
     * This handles the common case where the patch modifies a contiguous block of lines.
     * For complex multi-hunk patches, it falls back to the diff preview.
     */
    private fun tryApplySimplePatch(
        project: Project,
        document: Document,
        patch: String
    ): Boolean {
        try {
            val hunks = parseUnifiedDiffHunks(patch)
            if (hunks.isEmpty()) return false

            // Only handle single-hunk patches for automatic application
            if (hunks.size > 1) return false

            val hunk = hunks.first()

            // Validate the context lines match
            val docLines = document.text.lines()
            for ((offset, line) in hunk.contextAndRemoveLines) {
                val docLineIndex = hunk.oldStart - 1 + offset
                if (docLineIndex >= docLines.size) return false
                if (docLines[docLineIndex].trimEnd() != line.trimEnd()) {
                    log.info("Context mismatch at line ${docLineIndex + 1}: expected '${line.trim()}', got '${docLines[docLineIndex].trim()}'")
                    return false
                }
            }

            // Build the replacement text
            val startLine = hunk.oldStart - 1
            val endLine = startLine + hunk.oldCount
            if (endLine > docLines.size) return false

            val newLines = hunk.newLines
            val startOffset = document.getLineStartOffset(startLine)
            val endOffset = if (endLine <= docLines.size - 1) {
                document.getLineEndOffset(endLine - 1)
            } else {
                document.textLength
            }

            val replacement = newLines.joinToString("\n")

            WriteCommandAction.runWriteCommandAction(project, "Apply AI Suggestion", "AI Review", {
                document.replaceString(startOffset, endOffset, replacement)
            })

            return true

        } catch (e: Exception) {
            log.warn("Simple patch application failed", e)
            return false
        }
    }

    /**
     * Show a side-by-side diff preview when automatic application fails.
     */
    private fun showDiffPreview(
        project: Project,
        document: Document,
        patch: String,
        targetFile: File
    ) {
        val currentContent = document.text
        val patchedContent = applyPatchToText(currentContent, patch) ?: run {
            log.warn("Could not apply patch automatically. Patch content logged for debugging.")
            log.debug("Raw patch:\n$patch")
            Messages.showInfoMessage(
                project,
                "Could not apply patch automatically. Check the IDE log for details.",
                "AI Review - Manual Patch Required"
            )
            return
        }

        val factory = DiffContentFactory.getInstance()
        val leftContent = factory.create(project, currentContent)
        val rightContent = factory.create(project, patchedContent)

        val request = SimpleDiffRequest(
            "AI Review Suggestion - ${targetFile.name}",
            leftContent,
            rightContent,
            "Current",
            "Suggested"
        )

        DiffManager.getInstance().showDiff(project, request)
    }

    /**
     * Parse unified diff into hunks.
     */
    private fun parseUnifiedDiffHunks(patch: String): List<DiffHunk> {
        val hunks = mutableListOf<DiffHunk>()
        val lines = patch.lines()
        var i = 0

        while (i < lines.size) {
            val line = lines[i]
            if (line.startsWith("@@")) {
                val match = Regex("""@@ -(\d+),?(\d*) \+(\d+),?(\d*) @@""").find(line)
                if (match != null) {
                    val oldStart = match.groupValues[1].toInt()
                    val oldCount = match.groupValues[2].ifEmpty { "1" }.toInt()
                    val newStart = match.groupValues[3].toInt()
                    val newCount = match.groupValues[4].ifEmpty { "1" }.toInt()

                    val contextAndRemove = mutableListOf<Pair<Int, String>>()
                    val newLines = mutableListOf<String>()
                    var offset = 0
                    i++

                    while (i < lines.size && !lines[i].startsWith("@@")) {
                        val l = lines[i]
                        when {
                            l.startsWith("-") -> {
                                contextAndRemove.add(offset to l.substring(1))
                                offset++
                            }
                            l.startsWith("+") -> {
                                newLines.add(l.substring(1))
                            }
                            l.startsWith(" ") || l.isEmpty() -> {
                                val content = if (l.startsWith(" ")) l.substring(1) else l
                                contextAndRemove.add(offset to content)
                                newLines.add(content)
                                offset++
                            }
                            else -> break
                        }
                        i++
                    }

                    hunks.add(DiffHunk(oldStart, oldCount, newStart, newCount, contextAndRemove, newLines))
                    continue
                }
            }
            i++
        }
        return hunks
    }

    /**
     * Best-effort application of a unified diff patch to text content.
     */
    private fun applyPatchToText(content: String, patch: String): String? {
        return try {
            val hunks = parseUnifiedDiffHunks(patch)
            if (hunks.isEmpty()) return null

            val lines = content.lines().toMutableList()

            // Apply hunks in reverse order to preserve line numbers
            for (hunk in hunks.reversed()) {
                val startIdx = hunk.oldStart - 1
                val removeCount = hunk.oldCount

                if (startIdx < 0 || startIdx + removeCount > lines.size) return null

                for (j in 0 until removeCount) {
                    lines.removeAt(startIdx)
                }
                lines.addAll(startIdx, hunk.newLines)
            }

            lines.joinToString("\n")
        } catch (e: Exception) {
            log.warn("Failed to apply patch to text", e)
            null
        }
    }

    private data class DiffHunk(
        val oldStart: Int,
        val oldCount: Int,
        val newStart: Int,
        val newCount: Int,
        val contextAndRemoveLines: List<Pair<Int, String>>,
        val newLines: List<String>
    )
}
