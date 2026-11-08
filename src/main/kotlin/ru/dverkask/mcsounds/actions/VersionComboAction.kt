package ru.dverkask.mcsounds.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ex.ComboBoxAction
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareToggleAction
import ru.dverkask.mcsounds.McSoundsBundle
import ru.dverkask.mcsounds.catalog.SoundCatalogService
import ru.dverkask.mcsounds.settings.McSoundsSettings
import javax.swing.JComponent

class VersionComboAction : ComboBoxAction(), DumbAware {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val service = SoundCatalogService.getInstance()
        e.presentation.text = service.currentVersionId() ?: McSoundsBundle.message("version.latest")
        e.presentation.description = McSoundsBundle.message("version.description")
        e.presentation.isEnabled = service.versions.isNotEmpty()
    }

    override fun createPopupActionGroup(button: JComponent, dataContext: DataContext): DefaultActionGroup {
        val settings = McSoundsSettings.getInstance()
        val group = DefaultActionGroup()
        group.add(VersionItem("", McSoundsBundle.message("version.latest.item")))
        group.addSeparator()
        SoundCatalogService.getInstance().versions
            .filter { it.isRelease || settings.showSnapshots }
            .forEach { group.add(VersionItem(it.id, if (it.isRelease) it.id else McSoundsBundle.message("version.snapshot", it.id))) }
        return group
    }

    private class VersionItem(private val id: String, text: String) : DumbAwareToggleAction(text) {
        override fun getActionUpdateThread() = ActionUpdateThread.BGT
        override fun isSelected(e: AnActionEvent) = McSoundsSettings.getInstance().version == id
        override fun setSelected(e: AnActionEvent, state: Boolean) {
            SoundCatalogService.getInstance().selectVersion(id)
        }
    }
}
