package ru.dverkask.mcsounds.catalog

import com.google.gson.JsonParser
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiFile
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import ru.dverkask.mcsounds.model.SoundEvent
import ru.dverkask.mcsounds.model.SoundsJson

@Service(Service.Level.PROJECT)
class ProjectSounds(private val project: Project) {

    class Index(events: List<SoundEvent>) {
        private val byId: Map<String, SoundEvent> = events.associateBy { it.namespacedKey }
        private val byPath: Map<String, List<SoundEvent>> = events.groupBy { it.key }

        val events: Collection<SoundEvent> get() = byId.values
        fun isEmpty(): Boolean = byId.isEmpty()

        fun find(id: String): SoundEvent? =
            if (':' in id) byId[id] else byPath[id]?.singleOrNull()

        fun find(namespace: String, key: String): SoundEvent? = byId["$namespace:$key"]
    }

    fun index(): Index = CachedValuesManager.getManager(project).getCachedValue(project) {
        CachedValueProvider.Result.create(
            compute(),
            PsiModificationTracker.getInstance(project),
            VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS,
            DumbService.getInstance(project).modificationTracker,
        )
    }

    private fun compute(): Index {
        if (DumbService.isDumb(project)) return EMPTY
        val files = FilenameIndex.getVirtualFilesByName(SOUNDS_JSON, GlobalSearchScope.projectScope(project))
            .filter { namespaceOf(it) != null }
        if (files.isEmpty()) return EMPTY
        val assetDirs = files.mapNotNull { it.parent?.parent }.distinct()
        val events = files.flatMap { file ->
            val namespace = namespaceOf(file)!!
            runCatching {
                val json = JsonParser.parseString(textOf(file)).asJsonObject
                SoundsJson.parse(json, namespace) { ns, path -> locate(assetDirs, file.parent.parent, ns, path) }
            }.onFailure { LOG.debug("Skipping ${file.path}", it) }.getOrDefault(emptyList())
        }
        return Index(events)
    }

    private fun locate(assetDirs: List<VirtualFile>, own: VirtualFile, namespace: String, path: String): String? {
        val relative = "$namespace/sounds/$path.ogg"
        for (dir in listOf(own) + assetDirs) {
            dir.findFileByRelativePath(relative)?.let { return FileUtil.toSystemDependentName(it.path) }
        }
        return null
    }

    private fun textOf(file: VirtualFile): String =
        FileDocumentManager.getInstance().getCachedDocument(file)?.text ?: VfsUtilCore.loadText(file)

    companion object {
        private val LOG = logger<ProjectSounds>()
        private val EMPTY = Index(emptyList())
        const val SOUNDS_JSON = "sounds.json"

        fun getInstance(project: Project): ProjectSounds = project.getService(ProjectSounds::class.java)

        fun namespaceOf(file: VirtualFile): String? {
            if (file.name != SOUNDS_JSON) return null
            val nsDir = file.parent ?: return null
            if (nsDir.parent?.name != "assets") return null
            return nsDir.name
        }

        fun namespaceOf(file: PsiFile): String? = file.originalFile.virtualFile?.let { namespaceOf(it) }
    }
}
