package ru.dverkask.mcsounds.catalog

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

class CatalogPreloader : ProjectActivity {
    override suspend fun execute(project: Project) {
        val service = SoundCatalogService.getInstance()
        if (service.status == CatalogStatus.Idle && service.hasCachedIndex()) service.ensureLoaded()
    }
}
