package ru.dverkask.mcsounds.settings

import com.intellij.ide.actions.RevealFileAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.ColorPanel
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.RightGap
import com.intellij.ui.dsl.builder.selected
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel
import ru.dverkask.mcsounds.McSoundsBundle
import ru.dverkask.mcsounds.catalog.SoundCatalogService
import ru.dverkask.mcsounds.editor.PlayButtonStyle
import ru.dverkask.mcsounds.editor.PlayChipPreview
import kotlin.io.path.createDirectories

class McSoundsConfigurable : BoundConfigurable(McSoundsBundle.message("settings.display.name")) {

    override fun createPanel(): DialogPanel {
        val settings = McSoundsSettings.getInstance()
        val catalog = SoundCatalogService.getInstance()
        val sizeLabel = JBLabel(McSoundsBundle.message("settings.cache.calculating"))
        refreshSize(sizeLabel)

        return panel {
            group(McSoundsBundle.message("settings.group.playback")) {
                row {
                    checkBox(McSoundsBundle.message("settings.autoplay")).bindSelected(settings::autoplay)
                }
                row {
                    checkBox(McSoundsBundle.message("settings.file.attributes"))
                        .bindSelected(settings::applyFileAttributes)
                        .comment(McSoundsBundle.message("settings.file.attributes.comment"))
                }
            }
            group(McSoundsBundle.message("settings.group.versions")) {
                row {
                    checkBox(McSoundsBundle.message("settings.snapshots")).bindSelected(settings::showSnapshots)
                }
            }
            group(McSoundsBundle.message("settings.group.editor")) {
                val customColor = JBCheckBox(McSoundsBundle.message("settings.button.color"))
                val colorPanel = ColorPanel()
                val preview = PlayChipPreview {
                    if (customColor.isSelected) colorPanel.selectedColor ?: PlayButtonStyle.defaultAccent
                    else PlayButtonStyle.defaultAccent
                }
                colorPanel.addActionListener { preview.repaint() }
                customColor.addItemListener { preview.repaint() }
                row {
                    val custom = cell(customColor)
                    cell(colorPanel).enabledIf(custom.selected)
                    cell(preview).gap(RightGap.SMALL)
                }.rowComment(McSoundsBundle.message("settings.button.color.comment"))

                fun resetColor() {
                    val custom = settings.playButtonColor
                    customColor.isSelected = custom != null
                    colorPanel.selectedColor = custom ?: PlayButtonStyle.defaultAccent
                    preview.repaint()
                }
                resetColor()
                onReset { resetColor() }
                onIsModified {
                    val custom = settings.playButtonColor
                    customColor.isSelected != (custom != null) || (customColor.isSelected && colorPanel.selectedColor != custom)
                }
                onApply {
                    settings.playButtonColor = if (customColor.isSelected) colorPanel.selectedColor else null
                    PlayButtonStyle.repaintEditors()
                }

                row {
                    comment(McSoundsBundle.message("settings.gutter.comment"))
                }
            }
            group(McSoundsBundle.message("settings.group.cache")) {
                row(McSoundsBundle.message("settings.cache.location")) {
                    val path = catalog.cacheDir.toString()
                    link(StringUtil.shortenPathWithEllipsis(path, 56)) {
                        runCatching { catalog.cacheDir.createDirectories() }
                        RevealFileAction.openDirectory(catalog.cacheDir)
                    }.applyToComponent { toolTipText = path }
                }
                row(McSoundsBundle.message("settings.cache.size")) {
                    cell(sizeLabel)
                    button(McSoundsBundle.message("settings.cache.clear")) {
                        sizeLabel.text = McSoundsBundle.message("settings.cache.calculating")
                        ApplicationManager.getApplication().executeOnPooledThread {
                            catalog.clearCache()
                            refreshSize(sizeLabel)
                        }
                    }
                }
                row {
                    comment(McSoundsBundle.message("settings.cache.comment"))
                }
            }
        }
    }

    private fun refreshSize(label: JBLabel) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val size = SoundCatalogService.getInstance().cacheSizeBytes()
            ApplicationManager.getApplication().invokeLater({
                label.text = StringUtil.formatFileSize(size)
            }, ModalityState.any())
        }
    }
}
