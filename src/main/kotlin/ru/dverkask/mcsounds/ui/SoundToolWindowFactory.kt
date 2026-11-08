package ru.dverkask.mcsounds.ui

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

class SoundToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val content = ContentFactory.getInstance().createContent(null, "", false)
        val panel = SoundBrowserPanel(content)
        content.component = panel
        content.preferredFocusableComponent = panel
        content.isCloseable = false
        toolWindow.contentManager.addContent(content)
    }
}
