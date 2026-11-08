package ru.dverkask.mcsounds.editor

import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiTreeUtil
import ru.dverkask.mcsounds.catalog.ProjectSounds
import ru.dverkask.mcsounds.model.LegacySoundNames
import ru.dverkask.mcsounds.model.MINECRAFT
import ru.dverkask.mcsounds.model.SoundCatalog
import ru.dverkask.mcsounds.model.SoundCategory
import ru.dverkask.mcsounds.model.SoundEvent
import ru.dverkask.mcsounds.model.SoundFile
import ru.dverkask.mcsounds.model.SoundsJson

object SoundReferenceDetector {

    sealed interface Candidate {
        val displayName: String

        data class Constant(val name: String, val allowMojmap: Boolean, val legacy: Boolean = false) : Candidate {
            override val displayName: String get() = name
        }

        data class Key(val key: String) : Candidate {
            override val displayName: String get() = key
        }

        data class Project(val id: String) : Candidate {
            override val displayName: String get() = id
        }

        data class File(val namespace: String, val path: String, val localPath: String?) : Candidate {
            override val displayName: String get() = if (namespace == MINECRAFT) path else "$namespace:$path"
        }
    }

    private val QUALIFIERS = setOf("Sound", "Sounds", "SoundEvent", "SoundEvents", "SoundEffects", "SoundTypes")
    private val UPPER = Regex("[A-Z][A-Z0-9_]*")
    private val CONSTANT = Regex("[A-Z][A-Z0-9_]*_[A-Z0-9_]+")
    private val KEY = Regex("(minecraft:)?([a-z0-9_]+)(\\.[a-z0-9_]+)+")
    private val NAMESPACED = Regex("([a-z0-9_.-]+):([a-z0-9_./-]+)")
    private val FILE_PATH = Regex("(?:([a-z0-9_.-]+):)?([a-z0-9_.-]+(?:/[a-z0-9_.-]+)+)")
    private val STRING_IN_CODE = Regex("\"([a-z0-9_.:/-]+)\"")
    private val IMPORT = Regex("^\\s*import\\s+(?:static\\s+)?([\\w.]+)\\.(\\*|_|\\w+)", RegexOption.MULTILINE)
    private val CATEGORY_IDS = SoundCategory.entries.map { it.id }.filter { it.isNotEmpty() }.toSet()
    private val CATEGORY_PREFIXES = CATEGORY_IDS.map { it.uppercase() + "_" }

    fun candidate(element: PsiElement, resolveDeclarations: Boolean = false): Candidate? {
        if (element.firstChild != null || element is PsiComment || element is PsiWhiteSpace) return null
        val text = element.text
        val type = element.node?.elementType?.toString() ?: return null
        return when {
            type == "REGULAR_STRING_PART" -> {
                val prev = PsiTreeUtil.prevLeaf(element)?.node?.elementType?.toString()
                val next = PsiTreeUtil.nextLeaf(element)?.node?.elementType?.toString()
                if (prev == "OPEN_QUOTE" && next == "CLOSING_QUOTE") stringCandidate(text, element) else null
            }
            isQuoted(text) -> stringCandidate(text.trim(text[0]), element)
            type.equals("IDENTIFIER", ignoreCase = true) -> constantCandidate(element, resolveDeclarations)
            else -> null
        }
    }

    fun inlayOffset(element: PsiElement): Int {
        if (element.node?.elementType?.toString() == "REGULAR_STRING_PART") {
            PsiTreeUtil.nextLeaf(element)?.let { return it.textRange.endOffset }
        }
        return element.textRange.endOffset
    }

    fun resolve(candidate: Candidate, catalog: SoundCatalog?, project: Project?): SoundEvent? = when (candidate) {
        is Candidate.Constant -> catalog?.let { c ->
            c.byConstant(candidate.name, candidate.allowMojmap)
                ?: if (candidate.legacy) LegacySoundNames.keyOf(candidate.name)?.let { c.byKey(it) } else null
        }
        is Candidate.Key -> project?.let { ProjectSounds.getInstance(it).index().find(MINECRAFT, candidate.key) }
            ?: catalog?.byKey(candidate.key)
        is Candidate.Project -> project?.let { ProjectSounds.getInstance(it).index().find(candidate.id) }
        is Candidate.File -> {
            val file = SoundFile(candidate.path, namespace = candidate.namespace, localPath = candidate.localPath)
            SoundEvent(candidate.path, listOf(file), namespace = candidate.namespace)
                .takeIf { candidate.localPath != null || catalog?.hashOf(file) != null }
        }
    }

    fun needsCatalog(candidate: Candidate): Boolean = when (candidate) {
        is Candidate.Constant, is Candidate.Key -> true
        is Candidate.Project -> false
        is Candidate.File -> candidate.localPath == null
    }

    private fun isQuoted(text: String): Boolean =
        text.length >= 3 && (text[0] == '"' || text[0] == '\'') && text.last() == text[0]

    private fun stringCandidate(value: String, element: PsiElement): Candidate? {
        if (value.isEmpty() || value.length > 200) return null
        val file = element.containingFile
        val ownNamespace = file?.let { ProjectSounds.namespaceOf(it) }
        if (ownNamespace != null) soundsJsonCandidate(value, ownNamespace, element)?.let { return it }

        val namespaced = NAMESPACED.matchEntire(value)
        if (namespaced != null && namespaced.groupValues[1] != MINECRAFT) {
            val index = ProjectSounds.getInstance(element.project).index()
            return index.find(value)?.let { Candidate.Project(it.namespacedKey) }
        }
        val match = KEY.matchEntire(value) ?: return null
        if (match.groupValues[2] !in CATEGORY_IDS) {
            val key = value.removePrefix("minecraft:")
            return ProjectSounds.getInstance(element.project).index().find(MINECRAFT, key)?.let { Candidate.Key(key) }
        }
        return Candidate.Key(value.removePrefix("minecraft:"))
    }

    private fun soundsJsonCandidate(value: String, namespace: String, element: PsiElement): Candidate? {
        val index = ProjectSounds.getInstance(element.project).index()
        if (isPropertyName(element)) {
            index.find(namespace, value)?.let { return Candidate.Project(it.namespacedKey) }
            return null
        }
        val path = FILE_PATH.matchEntire(value) ?: return null
        val ns = path.groupValues[1].ifEmpty { MINECRAFT }
        val local = index.events.asSequence().flatMap { it.files }
            .firstOrNull { it.namespace == ns && it.name == path.groupValues[2] }?.localPath
        if (local == null && ns != MINECRAFT) return null
        return Candidate.File(ns, path.groupValues[2], local)
    }

    private fun isPropertyName(element: PsiElement): Boolean = nextSignificantLeaf(element)?.text == ":"

    private fun constantCandidate(element: PsiElement, resolveDeclarations: Boolean): Candidate? {
        val name = element.text
        if (!UPPER.matches(name) || insideImport(element)) return null
        val prev = prevSignificantLeaf(element)
        if (prev?.text == ".") {
            val qualifier = prevSignificantLeaf(prev)?.text ?: return null
            if (qualifier in QUALIFIERS) {
                val legacy = qualifier == "Sound" && LegacySoundNames.contains(name)
                if (CONSTANT.matches(name) || legacy) {
                    return Candidate.Constant(name, allowMojmap = qualifier == "SoundEvents", legacy = legacy)
                }
                return null
            }
            if (resolveDeclarations && qualifier.contains("sound", ignoreCase = true)) return projectConstant(element)
            return null
        }
        staticImportCandidate(element, name)?.let { return it }
        if (isDeclarationName(element)) return projectConstant(element)
        return null
    }

    private fun staticImportCandidate(element: PsiElement, name: String): Candidate? {
        if (isDeclarationName(element)) return null
        val file = element.containingFile ?: return null
        for ((qualifier, member) in importsOf(file)) {
            val wildcard = member == "*" || member == "_"
            if (!wildcard && member != name) continue
            val allowMojmap = qualifier == "SoundEvents"
            val legacy = qualifier == "Sound" && LegacySoundNames.contains(name)
            if (!CONSTANT.matches(name) && !legacy) continue
            if (wildcard && !allowMojmap && !legacy && CATEGORY_PREFIXES.none { name.startsWith(it) }) continue
            return Candidate.Constant(name, allowMojmap, legacy)
        }
        return null
    }

    private fun importsOf(file: PsiFile): List<Pair<String, String>> =
        CachedValuesManager.getCachedValue(file) {
            val imports = IMPORT.findAll(file.text.take(64 * 1024))
                .map { it.groupValues[1].substringAfterLast('.') to it.groupValues[2] }
                .filter { it.first in QUALIFIERS }
                .toList()
            CachedValueProvider.Result.create(imports, file)
        }

    private fun projectConstant(element: PsiElement): Candidate? {
        val index = ProjectSounds.getInstance(element.project).index()
        if (index.isEmpty()) return null
        val declaration = declarationOf(element) ?: return null
        val text = declaration.text
        if (text.length > 4000) return null
        for (literal in STRING_IN_CODE.findAll(text)) {
            index.find(literal.groupValues[1])?.let { return Candidate.Project(it.namespacedKey) }
        }
        val lower = element.text.lowercase()
        return (index.find(lower) ?: index.find(lower.replace('_', '.')))?.let { Candidate.Project(it.namespacedKey) }
    }

    private fun declarationOf(element: PsiElement): PsiElement? {
        val parent = element.parent ?: return null
        if (isDeclarationName(element)) return parent
        val target = parent.references.firstNotNullOfOrNull { it.resolve() } ?: return null
        val file = target.containingFile?.virtualFile ?: return null
        return if (ProjectFileIndex.getInstance(element.project).isInContent(file)) target else null
    }

    private fun isDeclarationName(element: PsiElement): Boolean {
        val parent = element.parent as? PsiNameIdentifierOwner ?: return false
        return parent.nameIdentifier == element
    }

    private fun insideImport(element: PsiElement): Boolean {
        var e: PsiElement? = element.parent
        var depth = 0
        while (e != null && e !is PsiFile && depth < 6) {
            if (e.node?.elementType?.toString()?.contains("IMPORT", ignoreCase = true) == true) return true
            e = e.parent
            depth++
        }
        return false
    }

    private fun prevSignificantLeaf(element: PsiElement): PsiElement? {
        var leaf = PsiTreeUtil.prevLeaf(element)
        while (leaf != null && (leaf is PsiWhiteSpace || leaf is PsiComment || leaf.textLength == 0)) {
            leaf = PsiTreeUtil.prevLeaf(leaf)
        }
        return leaf
    }

    private fun nextSignificantLeaf(element: PsiElement): PsiElement? {
        var leaf = PsiTreeUtil.nextLeaf(element)
        while (leaf != null && (leaf is PsiWhiteSpace || leaf is PsiComment || leaf.textLength == 0)) {
            leaf = PsiTreeUtil.nextLeaf(leaf)
        }
        return leaf
    }

    private val TEXT_KEY = Regex("(?<![\\w:./-])((?:[a-z0-9_.-]+:)?[a-z0-9_]+(?:\\.[a-z0-9_]+)+)(?![\\w./-])")

    fun textCandidates(text: CharSequence, element: PsiElement): List<Pair<Int, Candidate>> {
        if (text.length > 512 * 1024) return emptyList()
        return TEXT_KEY.findAll(text).mapNotNull { m ->
            stringCandidate(m.groupValues[1], element)?.let { m.range.last + 1 to it }
        }.toList()
    }
}
