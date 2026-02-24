package com.aireview.ui

import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JScrollPane

/**
 * Dialog for editing an existing review comment message.
 */
class EditCommentDialog(
    private val filePath: String,
    private val line: Int,
    currentMessage: String
) : DialogWrapper(true) {

    private val textArea = JBTextArea().apply {
        text = currentMessage
        lineWrap = true
        wrapStyleWord = true
    }

    init {
        title = "Edit Review Comment"
        isResizable = true
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout(0, 8))
        panel.preferredSize = Dimension(600, 300)
        panel.add(JBLabel("Comment for $filePath:$line"), BorderLayout.NORTH)
        panel.add(JScrollPane(textArea), BorderLayout.CENTER)
        return panel
    }

    override fun getPreferredFocusedComponent(): JComponent = textArea

    fun getMessage(): String = textArea.text.trim()
}
