package ru.dverkask.mcsounds

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.dverkask.mcsounds.model.SoundCatalog
import ru.dverkask.mcsounds.model.SoundEvent
import ru.dverkask.mcsounds.model.SoundFile
import ru.dverkask.mcsounds.ui.*
import javax.swing.tree.DefaultMutableTreeNode

class SoundTreeBuilderTest {

    private val catalog = SoundCatalog(
        "1.21",
        listOf(
            "entity.zombie.hurt", "entity.zombie.ambient", "entity.zombie.attack_wooden_door",
            "entity.player.levelup", "entity.player.attack.crit",
            "entity.arrow.shoot",
            "ambient.cave",
            "block.anvil.place",
        ).map { SoundEvent(it, listOf(SoundFile("f/$it"))) },
        emptyMap(),
    )

    private fun render(node: DefaultMutableTreeNode, indent: String = ""): String = buildString {
        for (i in 0 until node.childCount) {
            val child = node.getChildAt(i) as DefaultMutableTreeNode
            val label = when (child) {
                is FavoritesNode -> "Favorites"
                is CategoryNode -> child.category.displayName
                is GroupNode -> child.name + "/"
                is SoundLeafNode -> child.label
                else -> "?"
            }
            append(indent).append(label).append('\n')
            append(render(child, "$indent  "))
        }
    }

    @Test
    fun groupsByCategoryAndSecondSegment() {
        val result = SoundTreeBuilder.build(catalog, SoundQuery(""), setOf("block.anvil.place"))
        assertEquals(
            """
            Favorites
              block.anvil.place
            Ambient
              cave
            Blocks
              anvil.place
            Entities
              arrow.shoot
              player/
                attack.crit
                levelup
              zombie/
                ambient
                attack_wooden_door
                hurt
            """.trimIndent() + "\n",
            render(result.root),
        )
        assertEquals(8, result.matchCount)
    }

    @Test
    fun filtering() {
        val result = SoundTreeBuilder.build(catalog, SoundQuery("zombie a"), emptySet())
        assertEquals(2, result.matchCount)
        assertEquals("ambient", result.firstLeaf?.label)
    }
}
