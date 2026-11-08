package ru.dverkask.mcsounds

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import ru.dverkask.mcsounds.actions.CopyFormat
import ru.dverkask.mcsounds.model.SoundEvent
import ru.dverkask.mcsounds.model.SoundFile
import ru.dverkask.mcsounds.settings.McSoundsSettings

class CopyFormatTest : BasePlatformTestCase() {

    private val event = SoundEvent("entity.player.levelup", listOf(SoundFile("random/levelup")))

    fun testFormatsUseCurrentPitch() {
        val settings = McSoundsSettings.getInstance()
        val saved = settings.pitchPercent
        try {
            settings.pitchPercent = 150
            assertEquals("entity.player.levelup", CopyFormat.KEY.format(event))
            assertEquals("minecraft:entity.player.levelup", CopyFormat.NAMESPACED.format(event))
            assertEquals("Sound.ENTITY_PLAYER_LEVELUP", CopyFormat.BUKKIT_CONSTANT.format(event))
            assertEquals(
                "player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.5f);",
                CopyFormat.BUKKIT_CALL.format(event),
            )
            assertEquals(
                "Sound.sound(Key.key(\"entity.player.levelup\"), Sound.Source.MASTER, 1.0f, 1.5f)",
                CopyFormat.ADVENTURE.format(event),
            )
            assertEquals("/playsound minecraft:entity.player.levelup master @s ~ ~ ~ 1 1.5", CopyFormat.COMMAND.format(event))
            settings.pitchPercent = 100
            assertEquals("/playsound minecraft:entity.player.levelup master @s ~ ~ ~ 1 1.0", CopyFormat.COMMAND.format(event))
        } finally {
            settings.pitchPercent = saved
        }
    }

    fun testFavoritesPersistInState() {
        val settings = McSoundsSettings.getInstance()
        settings.setFavorite("entity.player.levelup", true)
        assertTrue(settings.isFavorite("entity.player.levelup"))
        settings.setFavorite("entity.player.levelup", false)
        assertFalse(settings.isFavorite("entity.player.levelup"))
    }
}
