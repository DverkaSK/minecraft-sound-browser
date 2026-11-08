package ru.dverkask.mcsounds

import org.junit.Assert.*
import org.junit.Test
import ru.dverkask.mcsounds.model.SoundCatalog
import ru.dverkask.mcsounds.model.SoundCategory
import ru.dverkask.mcsounds.model.SoundEvent
import ru.dverkask.mcsounds.model.SoundFile
import ru.dverkask.mcsounds.ui.SoundQuery
import ru.dverkask.mcsounds.ui.TimeFormat

class SoundModelTest {

    private fun event(key: String, vararg files: String) =
        SoundEvent(key, files.ifEmpty { arrayOf("x/$key") }.map { SoundFile(it) })

    private val catalog = SoundCatalog(
        "1.21",
        listOf(
            event("entity.player.levelup"),
            event("entity.zombie.hurt", "mob/zombie/hurt1", "mob/zombie/hurt2"),
            event("block.anvil.place"),
            event("ui.button.click"),
            event("music_disc.cat"),
            event("intentionally_empty"),
        ),
        emptyMap(),
    )

    @Test
    fun categories() {
        assertEquals(SoundCategory.ENTITY, catalog.byKey("entity.player.levelup")!!.category)
        assertEquals(SoundCategory.MUSIC_DISC, catalog.byKey("music_disc.cat")!!.category)
        assertEquals(SoundCategory.OTHER, catalog.byKey("intentionally_empty")!!.category)
    }

    @Test
    fun constantLookup() {
        assertEquals("entity.player.levelup", catalog.byConstant("ENTITY_PLAYER_LEVELUP")?.key)
        assertEquals("entity.player.levelup", catalog.byConstant("PLAYER_LEVELUP")?.key)
        assertEquals("block.anvil.place", catalog.byConstant("ANVIL_PLACE")?.key)
        assertNull(catalog.byConstant("PLAYER_LEVELUP", allowMojmap = false))
        assertEquals("ui.button.click", catalog.byKey("minecraft:ui.button.click")?.key)
    }

    @Test
    fun queryAcceptsAllNamingStyles() {
        val zombie = catalog.byKey("entity.zombie.hurt")!!
        for (q in listOf("zombie hurt", "entity.zombie.hurt", "ENTITY_ZOMBIE_HURT", "Sound.ENTITY_ZOMBIE_HURT",
            "SoundEvents.ZOMBIE_HURT", "minecraft:entity.zombie.hurt", "  hurt  ")) {
            assertTrue(q, SoundQuery(q).matches(zombie))
        }
        assertFalse(SoundQuery("zombie levelup").matches(zombie))
        assertTrue(SoundQuery("").isEmpty)
    }

    @Test
    fun highlightsAreMerged() {
        val ranges = SoundQuery("zom bie").highlights("zombie")
        assertEquals(listOf(0..5), ranges)
        assertEquals(listOf(3..5), SoundQuery("bie").highlights("ZomBIE"))
        assertEquals(listOf(0..9), SoundQuery("note_block").highlights("note_block"))
        assertEquals(listOf(0..3, 7..11), SoundQuery("note block").highlights("note_x_block"))
    }

    @Test
    fun timeFormat() {
        assertEquals("0:01.50", TimeFormat.format(1.5))
        assertEquals("2:05", TimeFormat.format(125.2))
    }
}
