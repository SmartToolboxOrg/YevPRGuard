package com.aireview.settings

import com.intellij.openapi.options.Configurable
import com.intellij.ui.components.JBTextArea
import javax.swing.*
import java.awt.Dimension
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets

/**
 * Settings UI panel registered under Tools > AI Review in IDE preferences.
 */
class ReviewSettingsConfigurable : Configurable {

    private var panel: JPanel? = null
    private var cliPathField: JTextField? = null
    private var modelField: JTextField? = null
    private var baseRefField: JTextField? = null
    private var timeoutField: JSpinner? = null
    private var maxDiffField: JSpinner? = null
    private var maxFileField: JSpinner? = null
    private var sendContentCheckbox: JCheckBox? = null
    private var ghCliPathField: JTextField? = null
    private var ghTokenField: JPasswordField? = null
    private var customPromptArea: JBTextArea? = null

    override fun getDisplayName(): String = "AI Review"

    override fun createComponent(): JComponent {
        val settings = ReviewSettings.getInstance().state

        panel = JPanel(GridBagLayout())
        val gbc = GridBagConstraints().apply {
            insets = Insets(4, 8, 4, 8)
            anchor = GridBagConstraints.WEST
            fill = GridBagConstraints.HORIZONTAL
        }

        var row = 0

        fun addRow(label: String, component: JComponent) {
            gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0.0; gbc.gridwidth = 1
            panel!!.add(JLabel(label), gbc)
            gbc.gridx = 1; gbc.weightx = 1.0
            panel!!.add(component, gbc)
            row++
        }

        cliPathField = JTextField(settings.claudeCliPath, 40).apply {
            toolTipText = "Leave empty to auto-detect"
        }
        addRow("Claude CLI path:", cliPathField!!)

        modelField = JTextField(settings.claudeModel, 30).apply {
            toolTipText = "Default (claude-sonnet-4-20250514)"
        }
        addRow("Model:", modelField!!)

        baseRefField = JTextField(settings.defaultBaseRef, 30)
        addRow("Default base ref:", baseRefField!!)

        timeoutField = JSpinner(SpinnerNumberModel(settings.requestTimeoutSeconds, 5, 600, 5))
        addRow("Request timeout (s):", timeoutField!!)

        maxDiffField = JSpinner(SpinnerNumberModel(settings.maxDiffSizeBytes, 10_000, 10_000_000, 50_000))
        addRow("Max diff size (bytes):", maxDiffField!!)

        maxFileField = JSpinner(SpinnerNumberModel(settings.maxFileContentBytes, 1_000, 5_000_000, 10_000))
        addRow("Max file content (bytes):", maxFileField!!)

        ghCliPathField = JTextField(settings.ghCliPath, 40).apply {
            toolTipText = "Leave empty to auto-detect"
        }
        addRow("GitHub CLI (gh) path:", ghCliPathField!!)

        ghTokenField = JPasswordField(settings.ghToken, 40).apply {
            toolTipText = "GitHub personal access token (used as GH_TOKEN env var for gh CLI authentication)"
        }
        addRow("GitHub Token (GH_TOKEN):", ghTokenField!!)

        // GitHub auth hint
        gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 2; gbc.weightx = 1.0
        gbc.insets = Insets(0, 8, 8, 8)
        val authHint = JLabel("<html><i>Or run <b>gh auth login</b> in terminal to authenticate without a token</i></html>")
        authHint.foreground = com.intellij.ui.JBColor.GRAY
        panel!!.add(authHint, gbc)
        gbc.insets = Insets(4, 8, 4, 8)
        row++

        sendContentCheckbox = JCheckBox("Send file content with requests", settings.sendFileContent)
        gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 2
        panel!!.add(sendContentCheckbox!!, gbc)
        row++

        // Custom review prompt label
        gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 2; gbc.weightx = 1.0
        gbc.insets = Insets(12, 8, 2, 8)
        panel!!.add(JLabel("Custom review instructions (appended to Claude prompt):"), gbc)
        row++

        // Custom review prompt text area
        customPromptArea = JBTextArea(4, 40).apply {
            text = settings.customReviewPrompt
            lineWrap = true
            wrapStyleWord = true
            toolTipText = "Additional instructions for the AI reviewer, e.g. \"Focus on security issues\" or \"Use Kotlin best practices\""
        }
        val scrollPane = JScrollPane(customPromptArea)
        scrollPane.preferredSize = Dimension(400, 80)
        gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 2; gbc.weightx = 1.0
        gbc.fill = GridBagConstraints.BOTH
        gbc.insets = Insets(2, 8, 4, 8)
        panel!!.add(scrollPane, gbc)
        row++

        // Push everything to the top
        gbc.gridy = row; gbc.weighty = 1.0; gbc.fill = GridBagConstraints.BOTH
        panel!!.add(JPanel(), gbc)

        return panel!!
    }

    override fun isModified(): Boolean {
        val s = ReviewSettings.getInstance().state
        return cliPathField?.text != s.claudeCliPath
                || modelField?.text != s.claudeModel
                || baseRefField?.text != s.defaultBaseRef
                || ((timeoutField?.value as? Number)?.toInt()) != s.requestTimeoutSeconds
                || ((maxDiffField?.value as? Number)?.toInt()) != s.maxDiffSizeBytes
                || ((maxFileField?.value as? Number)?.toInt()) != s.maxFileContentBytes
                || sendContentCheckbox?.isSelected != s.sendFileContent
                || ghCliPathField?.text != s.ghCliPath
                || String(ghTokenField?.password ?: charArrayOf()) != s.ghToken
                || customPromptArea?.text != s.customReviewPrompt
    }

    override fun apply() {
        val s = ReviewSettings.getInstance().state
        s.claudeCliPath = cliPathField?.text ?: s.claudeCliPath
        s.claudeModel = modelField?.text ?: s.claudeModel
        s.defaultBaseRef = baseRefField?.text ?: s.defaultBaseRef
        s.requestTimeoutSeconds = ((timeoutField?.value as? Number)?.toInt()) ?: s.requestTimeoutSeconds
        s.maxDiffSizeBytes = ((maxDiffField?.value as? Number)?.toInt()) ?: s.maxDiffSizeBytes
        s.maxFileContentBytes = ((maxFileField?.value as? Number)?.toInt()) ?: s.maxFileContentBytes
        s.sendFileContent = sendContentCheckbox?.isSelected ?: s.sendFileContent
        s.ghCliPath = ghCliPathField?.text ?: s.ghCliPath
        s.ghToken = ghTokenField?.password?.let { String(it) } ?: s.ghToken
        s.customReviewPrompt = customPromptArea?.text ?: s.customReviewPrompt
    }

    override fun reset() {
        val s = ReviewSettings.getInstance().state
        cliPathField?.text = s.claudeCliPath
        modelField?.text = s.claudeModel
        baseRefField?.text = s.defaultBaseRef
        timeoutField?.value = s.requestTimeoutSeconds
        maxDiffField?.value = s.maxDiffSizeBytes
        maxFileField?.value = s.maxFileContentBytes
        sendContentCheckbox?.isSelected = s.sendFileContent
        ghCliPathField?.text = s.ghCliPath
        ghTokenField?.text = s.ghToken
        customPromptArea?.text = s.customReviewPrompt
    }

    override fun disposeUIResources() {
        panel = null
    }
}
