package ru.dverkask.mcsounds

import com.intellij.openapi.util.IconLoader
import ru.dverkask.mcsounds.model.SoundCategory
import javax.swing.Icon

object McSoundsIcons {
    private fun load(path: String): Icon = IconLoader.getIcon(path, McSoundsIcons::class.java)

    @JvmField val ToolWindow = load("/icons/toolWindow.svg")
    @JvmField val Sound = load("/icons/sound.svg")
    @JvmField val Group = load("/icons/group.svg")
    @JvmField val Loop = load("/icons/loop.svg")
    @JvmField val Shuffle = load("/icons/shuffle.svg")
    @JvmField val Replay = load("/icons/replay.svg")

    private val categories: Map<SoundCategory, Icon> = SoundCategory.entries.associateWith {
        load("/icons/category/${it.id.ifEmpty { "other" }}.svg")
    }

    fun category(category: SoundCategory): Icon = categories.getValue(category)
}
