package com.aireview.ui

import com.aireview.action.GenerateCommitMessageAction
import com.aireview.action.GeneratePrDescriptionAction
import com.aireview.action.OneClickReviewAction
import com.aireview.action.PublishToGitHubAction
import com.aireview.model.FindingSource
import com.aireview.model.ReviewMode
import com.aireview.model.SelectableFinding
import com.aireview.model.Severity
import com.aireview.service.FindingsManager
import com.aireview.service.ReviewRunner
import com.aireview.settings.ReviewSettings
import com.aireview.util.PathUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.ToolWindow
import com.intellij.ui.CheckboxTree
import com.intellij.ui.CheckedTreeNode
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*
import javax.swing.tree.DefaultTreeModel

/**
 * Main UI panel for the AI Review tool window.
 *
 * Layout:
 * ┌────────────────────────────────────┐
 * │ [Mode ▼] [Base ref ____] [Head ___]│
 * │ [Run Review] [Clear] [Settings]    │
 * │ [Select All] [Deselect] [Publish]  │
 * │ ───────────────────────────────────│
 * │ Status label                       │
 * │ ───────────────────────────────────│
 * │  Findings checkbox tree            │
 * │                                    │
 * └────────────────────────────────────┘
 */
class ReviewToolWindowPanel(
    private val project: Project,
    private val toolWindow: ToolWindow
) : Disposable {
    private val rootPanel = JPanel(BorderLayout())
    private val statusLabel = JBLabel("Ready")

    // CheckboxTree for selectable findings
    private val rootNode = CheckedTreeNode("Findings")
    private val treeModel = DefaultTreeModel(rootNode)
    private val findingsTree = CheckboxTree(FindingCheckboxRenderer(), rootNode)

    // Controls
    private val modeCombo = JComboBox(arrayOf("Working Tree", "Commit Range"))
    private val baseRefField = JBTextField(ReviewSettings.getInstance().state.defaultBaseRef, 20)
    private val headRefField = JBTextField("HEAD", 15)
    private val headRefLabel = JLabel("Head ref:")
    private val runButton = JButton("Run Review")
    private val clearButton = JButton("Clear")
    private val settingsButton = JButton("Settings")
    private val prDescriptionButton = JButton("PR Description")
    private val commitMessageButton = JButton("Commit Message")
    private val reviewAndCreatePrButton = JButton("Review & Create PR")
    private val selectAllButton = JButton("Select All")
    private val deselectAllButton = JButton("Deselect All")
    private val publishButton = JButton("Publish Comments to PR")

    // Listener reference for cleanup
    private val findingsListener: () -> Unit = { updateTree() }

    // Flag to suppress checkbox change events during tree rebuild
    @Volatile
    private var updatingTree = false

    init {
        setupUI()
        setupListeners()

        // Listen for findings changes to update tree
        FindingsManager.getInstance(project).addListener(findingsListener)
    }

    fun getContent(): JComponent = rootPanel

    override fun dispose() {
        FindingsManager.getInstance(project).removeListener(findingsListener)
    }

    private fun setupUI() {
        // Top config panel
        val configPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = BorderFactory.createEmptyBorder(4, 4, 4, 4)

            // Row 1: Mode + refs
            val refRow = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2))
            refRow.add(JLabel("Mode:"))
            refRow.add(modeCombo)
            refRow.add(JLabel("Base ref:"))
            refRow.add(baseRefField)
            refRow.add(headRefLabel)
            refRow.add(headRefField)
            add(refRow)

            // Row 2: Action buttons
            val buttonRow = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2))
            buttonRow.add(runButton)
            buttonRow.add(reviewAndCreatePrButton)
            buttonRow.add(clearButton)
            buttonRow.add(settingsButton)
            buttonRow.add(prDescriptionButton)
            buttonRow.add(commitMessageButton)
            add(buttonRow)

            // Row 3: Selection + Publish buttons
            val selectionRow = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2))
            selectionRow.add(selectAllButton)
            selectionRow.add(deselectAllButton)
            selectionRow.add(publishButton)
            add(selectionRow)

            // Status bar
            val statusRow = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0))
            statusRow.add(statusLabel)
            add(statusRow)
        }

        // Initially hide commit range fields
        headRefLabel.isVisible = false
        headRefField.isVisible = false

        // Tree setup
        findingsTree.isRootVisible = false
        findingsTree.showsRootHandles = true

        rootPanel.add(configPanel, BorderLayout.NORTH)
        rootPanel.add(JBScrollPane(findingsTree), BorderLayout.CENTER)
    }

    private fun setupListeners() {
        modeCombo.addActionListener {
            val isRange = modeCombo.selectedIndex == 1
            headRefLabel.isVisible = isRange
            headRefField.isVisible = isRange
        }

        runButton.addActionListener { runReview() }

        clearButton.addActionListener {
            FindingsManager.getInstance(project).clear()
            statusLabel.text = "Cleared"
        }

        settingsButton.addActionListener {
            ShowSettingsUtil.getInstance()
                .showSettingsDialog(project, "AI Review")
        }

        prDescriptionButton.addActionListener {
            GeneratePrDescriptionAction.generatePrDescription(project)
        }

        commitMessageButton.addActionListener {
            GenerateCommitMessageAction.generateCommitMessage(project)
        }

        reviewAndCreatePrButton.addActionListener {
            OneClickReviewAction.runFullPipeline(project)
        }

        selectAllButton.addActionListener {
            FindingsManager.getInstance(project).selectAll()
        }

        deselectAllButton.addActionListener {
            FindingsManager.getInstance(project).deselectAll()
        }

        publishButton.addActionListener {
            val action = ActionManager.getInstance().getAction("com.aireview.action.PublishToGitHub")
            if (action != null) {
                val event = com.intellij.openapi.actionSystem.AnActionEvent.createFromAnAction(
                    action,
                    null,
                    "AIReviewToolWindow",
                    com.intellij.openapi.actionSystem.DataContext { dataId ->
                        when (dataId) {
                            com.intellij.openapi.actionSystem.CommonDataKeys.PROJECT.name -> project
                            else -> null
                        }
                    }
                )
                action.actionPerformed(event)
            }
        }

        // Navigate to file+line on double-click
        findingsTree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) {
                    val path = findingsTree.getPathForLocation(e.x, e.y) ?: return
                    val node = path.lastPathComponent as? CheckedTreeNode ?: return
                    val sf = node.userObject as? SelectableFinding ?: return
                    navigateToFinding(sf.finding)
                }
            }
        })

        // Right-click context menu for editing/deleting comments
        findingsTree.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                handlePopup(e)
            }
            override fun mouseReleased(e: MouseEvent) {
                handlePopup(e)
            }
            private fun handlePopup(e: MouseEvent) {
                if (!e.isPopupTrigger) return
                val path = findingsTree.getPathForLocation(e.x, e.y) ?: return
                val node = path.lastPathComponent as? CheckedTreeNode ?: return
                val sf = node.userObject as? SelectableFinding ?: return

                findingsTree.selectionPath = path
                val popup = JPopupMenu()

                // Edit is available for all findings (AI and manual)
                val editItem = JMenuItem("Edit Comment")
                editItem.addActionListener {
                    val dialog = EditCommentDialog(sf.finding.filePath, sf.finding.line ?: 0, sf.finding.message)
                    if (dialog.showAndGet()) {
                        val newMessage = dialog.getMessage()
                        if (newMessage.isNotEmpty() && newMessage != sf.finding.message) {
                            FindingsManager.getInstance(project).updateFindingMessage(sf.id, newMessage)
                        }
                    }
                }
                popup.add(editItem)

                // Publish this single comment to PR
                val publishItem = JMenuItem("Publish to PR")
                publishItem.addActionListener {
                    PublishToGitHubAction.publishFindings(project, listOf(sf))
                }
                popup.add(publishItem)

                // Delete comment
                popup.addSeparator()
                val deleteItem = JMenuItem("Delete Comment")
                deleteItem.addActionListener {
                    FindingsManager.getInstance(project).removeFinding(sf.id)
                }
                popup.add(deleteItem)

                popup.show(findingsTree, e.x, e.y)
            }
        })

        // Checkbox toggle listener
        findingsTree.addCheckboxTreeListener(object : com.intellij.ui.CheckboxTreeListener {
            override fun nodeStateChanged(node: CheckedTreeNode) {
                if (updatingTree) return
                val obj = node.userObject
                val manager = FindingsManager.getInstance(project)
                when (obj) {
                    is SelectableFinding -> {
                        // Sync finding selection with checkbox state
                        if (obj.selected != node.isChecked) {
                            manager.toggleSelection(obj.id)
                        }
                    }
                    is String -> {
                        // File node: toggle all children
                        val checked = node.isChecked
                        for (i in 0 until node.childCount) {
                            val child = node.getChildAt(i) as? CheckedTreeNode ?: continue
                            val childSf = child.userObject as? SelectableFinding ?: continue
                            if (childSf.selected != checked) {
                                manager.toggleSelection(childSf.id)
                            }
                        }
                    }
                }
            }
        })
    }

    private fun runReview(fileFilter: String? = null) {
        val mode = if (modeCombo.selectedIndex == 0) ReviewMode.WORKTREE else ReviewMode.RANGE
        val baseRef = baseRefField.text.trim().ifEmpty { "origin/main" }
        val headRef = headRefField.text.trim().ifEmpty { "HEAD" }

        runButton.isEnabled = false
        statusLabel.text = "Running review..."

        ReviewRunner(project).run(
            baseRef = baseRef,
            headRef = headRef,
            mode = mode,
            fileFilter = fileFilter,
            onComplete = { result ->
                // Callback is already invoked on EDT by ReviewRunner
                runButton.isEnabled = true
                val count = result.findings.size
                statusLabel.text = "Review complete: $count finding(s)"
            },
            onError = { error ->
                // Callback is already invoked on EDT by ReviewRunner
                runButton.isEnabled = true
                statusLabel.text = "Error: $error"
            }
        )
    }

    private fun updateTree() {
        ApplicationManager.getApplication().invokeLater {
            updatingTree = true
            try {
                val root = CheckedTreeNode("Findings")
                val manager = FindingsManager.getInstance(project)
                val allFindings = manager.getSelectableFindings()

                // Group by file
                val byFile = allFindings.groupBy { it.finding.filePath.replace('\\', '/') }

                for ((filePath, findings) in byFile.toSortedMap()) {
                    val fileNode = CheckedTreeNode(filePath)
                    // Sort: errors first, then by line
                    val sorted = findings.sortedWith(
                        compareByDescending<SelectableFinding> { it.finding.severityEnum.ordinal }
                            .thenBy { it.finding.line ?: 0 }
                    )
                    var allSelected = true
                    for (sf in sorted) {
                        val findingNode = CheckedTreeNode(sf)
                        findingNode.isChecked = sf.selected
                        fileNode.add(findingNode)
                        if (!sf.selected) allSelected = false
                    }
                    fileNode.isChecked = allSelected
                    root.add(fileNode)
                }

                treeModel.setRoot(root)
                treeModel.reload()
                findingsTree.model = treeModel

                // Expand all nodes
                for (i in 0 until findingsTree.rowCount) {
                    findingsTree.expandRow(i)
                }
            } finally {
                updatingTree = false
            }
        }
    }

    fun setStatusText(text: String) {
        ApplicationManager.getApplication().invokeLater {
            statusLabel.text = text
        }
    }

    private fun navigateToFinding(finding: com.aireview.model.ReviewFinding) {
        val basePath = project.basePath ?: return
        val file = PathUtil.resolveAndValidate(basePath, finding.filePath) ?: return
        val vf = LocalFileSystem.getInstance().findFileByIoFile(file) ?: return

        // Line numbers in findings are 1-based; OpenFileDescriptor expects 0-based
        val line = ((finding.line ?: 1) - 1).coerceAtLeast(0)
        val descriptor = OpenFileDescriptor(project, vf, line, 0)
        FileEditorManager.getInstance(project).openTextEditor(descriptor, true)
    }

    /**
     * Custom checkbox tree cell renderer that shows severity icons and formatted text.
     * Manual comments show with [Manual] prefix in blue.
     */
    private class FindingCheckboxRenderer : CheckboxTree.CheckboxTreeCellRenderer() {
        override fun customizeRenderer(
            tree: JTree?,
            value: Any?,
            selected: Boolean,
            expanded: Boolean,
            leaf: Boolean,
            row: Int,
            hasFocus: Boolean
        ) {
            val node = value as? CheckedTreeNode ?: return
            val renderer = textRenderer

            when (val obj = node.userObject) {
                is String -> {
                    // File path node
                    renderer.append(obj)
                }
                is SelectableFinding -> {
                    val finding = obj.finding
                    if (obj.source == FindingSource.MANUAL) {
                        renderer.append(
                            "[Manual] ",
                            com.intellij.ui.SimpleTextAttributes(
                                com.intellij.ui.SimpleTextAttributes.STYLE_BOLD,
                                JBColor(0x4A86C8, 0x6897BB)
                            )
                        )
                        renderer.append(
                            "L${finding.line ?: "?"}: ${finding.message}",
                            com.intellij.ui.SimpleTextAttributes(
                                com.intellij.ui.SimpleTextAttributes.STYLE_PLAIN,
                                JBColor(0x4A86C8, 0x6897BB)
                            )
                        )
                    } else {
                        val prefix = when (finding.severityEnum) {
                            Severity.ERROR -> "\u2716 "   // ✖
                            Severity.WARNING -> "\u26A0 " // ⚠
                            Severity.INFO -> "\u2139 "    // ℹ
                        }
                        val color = when (finding.severityEnum) {
                            Severity.ERROR -> JBColor.RED
                            Severity.WARNING -> JBColor.ORANGE
                            Severity.INFO -> JBColor.GRAY
                        }
                        val ruleTag = finding.ruleId?.let { " [$it]" } ?: ""
                        renderer.append(
                            "${prefix}L${finding.line ?: "?"}$ruleTag: ${finding.message}",
                            com.intellij.ui.SimpleTextAttributes(
                                com.intellij.ui.SimpleTextAttributes.STYLE_PLAIN,
                                color
                            )
                        )
                    }
                }
            }
        }
    }
}
