package com.aireview.ui

import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JScrollPane

/**
 * Preview/edit dialog for AI-generated PR title and description before publishing.
 */
class PublishPreviewDialog(
    private val prNumber: Int,
    private val commentCount: Int,
    initialTitle: String,
    initialDescription: String,
    okButtonText: String = "Publish"
) : DialogWrapper(true) {

    private val titleField = JBTextField(initialTitle)

    private val descriptionArea = JBTextArea(initialDescription).apply {
        lineWrap = true
        wrapStyleWord = true
    }

    init {
        title = "Publish to GitHub PR"
        setOKButtonText(okButtonText)
        isResizable = true
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout(0, 8))
        panel.preferredSize = Dimension(700, 500)

        val infoLabel = if (commentCount > 0) {
            val commentWord = if (commentCount == 1) "comment" else "comments"
            JBLabel("PR #$prNumber — $commentCount $commentWord will be published")
        } else {
            JBLabel("PR #$prNumber")
        }

        val topPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(infoLabel)
            add(javax.swing.Box.createVerticalStrut(8))
            add(JBLabel("Title:"))
            add(javax.swing.Box.createVerticalStrut(4))
            add(titleField)
            add(javax.swing.Box.createVerticalStrut(8))
            add(JBLabel("Description:"))
        }

        panel.add(topPanel, BorderLayout.NORTH)
        panel.add(JScrollPane(descriptionArea), BorderLayout.CENTER)

        return panel
    }

    override fun getPreferredFocusedComponent(): JComponent = titleField

    fun getPrTitle(): String = titleField.text.trim()

    fun getDescription(): String = descriptionArea.text.trim()
}
