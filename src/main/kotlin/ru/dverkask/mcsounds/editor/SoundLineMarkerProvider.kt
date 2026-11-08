package ru.dverkask.mcsounds.editor

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.codeInsight.daemon.GutterIconNavigationHandler
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProviderDescriptor
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.ProjectManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import ru.dverkask.mcsounds.McSoundsBundle
import ru.dverkask.mcsounds.audio.PlayerState
import ru.dverkask.mcsounds.audio.SoundPlayer
import ru.dverkask.mcsounds.catalog.CatalogStatus
import ru.dverkask.mcsounds.catalog.SoundCatalogListener
import ru.dverkask.mcsounds.catalog.SoundCatalogService
import javax.swing.Icon

class SoundLineMarkerProvider : LineMarkerProviderDescriptor() {

    override fun getName(): String = McSoundsBundle.message("gutter.name")

    override fun getIcon(): Icon = PlayGutterIcon

    override fun isEnabledByDefault(): Boolean = false

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        val candidate = SoundReferenceDetector.candidate(element) ?: return null
        val service = SoundCatalogService.getInstance()
        val catalog = service.catalog
        if (catalog == null && SoundReferenceDetector.needsCatalog(candidate)) {
            if (service.status == CatalogStatus.Idle) service.ensureLoaded()
            return null
        }
        val event = SoundReferenceDetector.resolve(candidate, catalog, element.project) ?: return null
        val key = event.displayKey

        val handler = GutterIconNavigationHandler<PsiElement> { _, _ ->
            val player = SoundPlayer.getInstance()
            val current = player.event
            if (current?.namespacedKey == event.namespacedKey && player.state == PlayerState.PLAYING) {
                player.stop()
            } else {
                val fresh = if (event.isVanilla) SoundCatalogService.getInstance().catalog?.byKey(event.key) ?: event else event
                player.open(fresh, play = true)
            }
        }

        return LineMarkerInfo(
            element,
            element.textRange,
            PlayGutterIcon,
            { McSoundsBundle.message("gutter.tooltip", key, event.files.size) },
            handler,
            GutterIconRenderer.Alignment.LEFT,
            { McSoundsBundle.message("gutter.accessible", key) },
        )
    }
}

class GutterRefresher : SoundCatalogListener {
    override fun statusChanged(status: CatalogStatus) {
        if (status !is CatalogStatus.Ready) return
        for (project in ProjectManager.getInstance().openProjects) {
            if (project.isDisposed) continue
            val files = ReadAction.compute<List<PsiFile>, RuntimeException> {
                FileEditorManager.getInstance(project).openFiles.mapNotNull { PsiManager.getInstance(project).findFile(it) }
            }
            files.forEach { DaemonCodeAnalyzer.getInstance(project).restart(it) }
        }
    }
}
