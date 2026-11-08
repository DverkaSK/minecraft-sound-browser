package ru.dverkask.mcsounds.actions

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataKey
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.DumbAwareToggleAction
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.util.messages.Topic
import ru.dverkask.mcsounds.McSoundsBundle
import ru.dverkask.mcsounds.audio.SoundPlayer
import ru.dverkask.mcsounds.catalog.CatalogStatus
import ru.dverkask.mcsounds.catalog.SoundCatalogService
import ru.dverkask.mcsounds.model.MINECRAFT
import ru.dverkask.mcsounds.model.SoundEvent
import ru.dverkask.mcsounds.settings.McSoundsConfigurable
import ru.dverkask.mcsounds.settings.McSoundsSettings
import java.awt.datatransfer.StringSelection
import java.util.Locale

object McSoundsDataKeys {
    @JvmField
    val SOUND_EVENT: DataKey<SoundEvent> = DataKey.create("McSounds.SoundEvent")
}

fun interface FavoritesListener {
    fun favoritesChanged()

    companion object {
        @Topic.AppLevel
        val TOPIC: Topic<FavoritesListener> = Topic.create("Minecraft sound favorites", FavoritesListener::class.java)
    }
}

enum class CopyFormat(private val vanillaOnly: Boolean = false, private val render: (SoundEvent, String) -> String) {
    KEY(render = { e, _ -> e.displayKey }),
    NAMESPACED(render = { e, _ -> e.namespacedKey }),
    BUKKIT_CONSTANT(vanillaOnly = true, render = { e, _ -> "Sound.${e.constantName}" }),
    BUKKIT_CALL(vanillaOnly = true, render = { e, p -> "player.playSound(player.getLocation(), Sound.${e.constantName}, 1.0f, ${p}f);" }),
    ADVENTURE(render = { e, p -> "Sound.sound(Key.key(\"${e.displayKey}\"), Sound.Source.MASTER, 1.0f, ${p}f)" }),
    COMMAND(render = { e, p -> "/playsound ${e.namespacedKey} master @s ~ ~ ~ 1 $p" });

    fun appliesTo(event: SoundEvent): Boolean = !vanillaOnly || event.namespace == MINECRAFT

    fun format(event: SoundEvent): String = render(event, pitchText())

    companion object {
        fun pitchText(): String {
            val pitch = McSoundsSettings.getInstance().pitchPercent / 100.0
            val text = String.format(Locale.ROOT, "%.2f", pitch).trimEnd('0')
            return if (text.endsWith('.')) text + "0" else text
        }
    }
}

abstract class SoundAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT
    protected fun event(e: AnActionEvent): SoundEvent? = e.getData(McSoundsDataKeys.SOUND_EVENT)
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = event(e) != null
    }
}

class PlaySoundAction : SoundAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val event = event(e) ?: return
        SoundPlayer.getInstance().open(event, play = true)
    }
}

abstract class CopySoundAction(private val format: CopyFormat) : SoundAction() {
    override fun update(e: AnActionEvent) {
        val event = event(e)
        e.presentation.isEnabled = event != null && format.appliesTo(event)
        e.presentation.description = event?.takeIf { format.appliesTo(it) }?.let { format.format(it) }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val event = event(e) ?: return
        CopyPasteManager.getInstance().setContents(StringSelection(format.format(event)))
    }
}

class CopyKeyAction : CopySoundAction(CopyFormat.KEY)
class CopyNamespacedKeyAction : CopySoundAction(CopyFormat.NAMESPACED)
class CopyBukkitConstantAction : CopySoundAction(CopyFormat.BUKKIT_CONSTANT)
class CopyBukkitCallAction : CopySoundAction(CopyFormat.BUKKIT_CALL)
class CopyAdventureAction : CopySoundAction(CopyFormat.ADVENTURE)
class CopyCommandAction : CopySoundAction(CopyFormat.COMMAND)

class ToggleFavoriteAction : DumbAwareToggleAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun isSelected(e: AnActionEvent): Boolean {
        val event = e.getData(McSoundsDataKeys.SOUND_EVENT) ?: return false
        return McSoundsSettings.getInstance().isFavorite(event.key)
    }

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        val event = e.getData(McSoundsDataKeys.SOUND_EVENT) ?: return
        McSoundsSettings.getInstance().setFavorite(event.key, state)
    }

    override fun update(e: AnActionEvent) {
        super.update(e)
        val event = e.getData(McSoundsDataKeys.SOUND_EVENT)
        val favorite = event != null && McSoundsSettings.getInstance().isFavorite(event.key)
        e.presentation.isEnabled = event != null && event.isVanilla
        e.presentation.icon = if (favorite) AllIcons.Nodes.Favorite else AllIcons.Nodes.NotFavoriteOnHover
        e.presentation.text = McSoundsBundle.message(if (favorite) "action.favorite.remove" else "action.favorite.add")
    }
}

class RefreshCatalogAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = SoundCatalogService.getInstance().status !is CatalogStatus.Loading
    }

    override fun actionPerformed(e: AnActionEvent) {
        SoundCatalogService.getInstance().reload(refreshManifest = true)
    }
}

class AutoplayToggleAction : DumbAwareToggleAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun isSelected(e: AnActionEvent) = McSoundsSettings.getInstance().autoplay
    override fun setSelected(e: AnActionEvent, state: Boolean) {
        McSoundsSettings.getInstance().autoplay = state
    }
}

class OpenSettingsAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun actionPerformed(e: AnActionEvent) {
        ShowSettingsUtil.getInstance().showSettingsDialog(e.project, McSoundsConfigurable::class.java)
    }
}

class ShowSoundBrowserAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID)?.activate(null)
    }

    companion object {
        const val TOOL_WINDOW_ID = "Minecraft Sounds"
    }
}
