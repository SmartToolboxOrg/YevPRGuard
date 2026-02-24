package com.aireview.ui

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

/**
 * Factory that creates the "AI Review" tool window.
 * Registered in plugin.xml. DumbAware allows it to work during indexing.
 */
class ReviewToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = ReviewToolWindowPanel(project, toolWindow)
        val content = ContentFactory.getInstance().createContent(panel.getContent(), "", false)
        Disposer.register(content, panel)
        toolWindow.contentManager.addContent(content)
    }
}
