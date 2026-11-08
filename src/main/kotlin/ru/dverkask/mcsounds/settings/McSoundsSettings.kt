package ru.dverkask.mcsounds.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.*
import com.intellij.ui.ColorUtil
import com.intellij.util.xmlb.annotations.XCollection
import ru.dverkask.mcsounds.actions.FavoritesListener
import java.awt.Color

@Service(Service.Level.APP)
@State(name = "MinecraftSoundBrowser", storages = [Storage("minecraftSoundBrowser.xml")])
class McSoundsSettings : SimplePersistentStateComponent<McSoundsSettings.State>(State()) {

    class State : BaseState() {
        var version by string("")
        var volume by property(80)
        var pitch by property(100)
        var loop by property(false)
        var autoplay by property(true)
        var showSnapshots by property(false)
        var applyFileAttributes by property(true)
        var playButtonColor by string("")

        @get:XCollection(style = XCollection.Style.v2)
        var favorites by stringSet()
    }

    var version: String
        get() = state.version.orEmpty()
        set(value) { state.version = value }

    var volume: Int
        get() = state.volume.coerceIn(0, 100)
        set(value) { state.volume = value.coerceIn(0, 100) }

    var pitchPercent: Int
        get() = state.pitch.coerceIn(MIN_PITCH, MAX_PITCH)
        set(value) { state.pitch = value.coerceIn(MIN_PITCH, MAX_PITCH) }

    var loop: Boolean
        get() = state.loop
        set(value) { state.loop = value }

    var autoplay: Boolean
        get() = state.autoplay
        set(value) { state.autoplay = value }

    var showSnapshots: Boolean
        get() = state.showSnapshots
        set(value) { state.showSnapshots = value }

    var applyFileAttributes: Boolean
        get() = state.applyFileAttributes
        set(value) { state.applyFileAttributes = value }

    var playButtonColor: Color?
        get() = state.playButtonColor?.takeIf { it.isNotBlank() }?.let { runCatching { ColorUtil.fromHex(it) }.getOrNull() }
        set(value) { state.playButtonColor = value?.let { ColorUtil.toHex(it) } ?: "" }

    fun isFavorite(key: String): Boolean = key in state.favorites

    fun favorites(): Set<String> = state.favorites.toSortedSet()

    fun setFavorite(key: String, favorite: Boolean) {
        if (favorite == isFavorite(key)) return
        state.favorites = (if (favorite) state.favorites + key else state.favorites - key).toMutableSet()
        ApplicationManager.getApplication().messageBus.syncPublisher(FavoritesListener.TOPIC).favoritesChanged()
    }

    companion object {
        const val MIN_PITCH = 50
        const val MAX_PITCH = 200

        fun getInstance(): McSoundsSettings =
            ApplicationManager.getApplication().getService(McSoundsSettings::class.java)
    }
}
