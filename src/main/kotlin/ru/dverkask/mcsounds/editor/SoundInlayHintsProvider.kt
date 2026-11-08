package ru.dverkask.mcsounds.editor

import com.intellij.codeInsight.hints.*
import com.intellij.lang.Language
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiPlainText
import ru.dverkask.mcsounds.editor.SoundReferenceDetector.Candidate
import ru.dverkask.mcsounds.McSoundsBundle
import ru.dverkask.mcsounds.catalog.CatalogStatus
import ru.dverkask.mcsounds.catalog.SoundCatalogService
import ru.dverkask.mcsounds.ui.MiniPlayerPopup
import java.awt.Cursor
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingUtilities

@Suppress("UnstableApiUsage")
class SoundInlayHintsProvider : InlayHintsProvider<NoSettings> {

    override val key: SettingsKey<NoSettings> = SettingsKey("ru.dverkask.mcsounds.play")
    override val name: String get() = McSoundsBundle.message("inlay.name")
    override val description: String get() = McSoundsBundle.message("inlay.description")
    override val group: InlayGroup get() = InlayGroup.OTHER_GROUP
    override val previewText: String? = null

    override fun createSettings(): NoSettings = NoSettings()

    override fun isLanguageSupported(language: Language): Boolean = true

    override fun createConfigurable(settings: NoSettings): ImmediateConfigurable = object : ImmediateConfigurable {
        override fun createComponent(listener: ChangeListener): JComponent = JPanel()
    }

    override fun getCollectorFor(file: PsiFile, editor: Editor, settings: NoSettings, sink: InlayHintsSink): InlayHintsCollector =
        object : FactoryInlayHintsCollector(editor) {
            override fun collect(element: PsiElement, editor: Editor, sink: InlayHintsSink): Boolean {
                if (element is PsiPlainText) {
                    val start = element.textRange.startOffset
                    for ((end, candidate) in SoundReferenceDetector.textCandidates(element.text, element)) {
                        addButton(element, candidate, start + end, sink)
                    }
                    return false
                }
                val candidate = SoundReferenceDetector.candidate(element, resolveDeclarations = true) ?: return true
                addButton(element, candidate, SoundReferenceDetector.inlayOffset(element), sink)
                return true
            }

            private fun addButton(element: PsiElement, candidate: Candidate, offset: Int, sink: InlayHintsSink) {
                val service = SoundCatalogService.getInstance()
                val catalog = service.catalog
                val needsCatalog = SoundReferenceDetector.needsCatalog(candidate)
                val event = SoundReferenceDetector.resolve(candidate, catalog, element.project)
                if (event == null && (catalog != null || !needsCatalog)) return
                if (catalog == null && needsCatalog && service.status == CatalogStatus.Idle) service.ensureLoaded()

                val chip = PlayChipPresentation(editor) { mouse ->
                    val at = SwingUtilities.convertPoint(mouse.component, mouse.point, editor.contentComponent)
                    MiniPlayerPopup.showReference(editor, candidate, MiniPlayerPopup.anchorFor(editor, at))
                }
                val withCursor = factory.withCursorOnHover(chip, Cursor.getPredefinedCursor(Cursor.HAND_CURSOR))
                val tooltip = if (event != null) McSoundsBundle.message("inlay.tooltip", event.displayKey, event.files.size)
                else McSoundsBundle.message("inlay.tooltip.unresolved", candidate.displayName)
                sink.addInlineElement(offset, true, factory.withTooltip(tooltip, withCursor), false)
            }
        }
}
