package ru.dverkask.mcsounds

import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import ru.dverkask.mcsounds.editor.SoundReferenceDetector
import ru.dverkask.mcsounds.editor.SoundReferenceDetector.Candidate

class SoundReferenceDetectorTest : BasePlatformTestCase() {

    private fun candidates(fileName: String, text: String): List<Candidate> {
        val file = myFixture.configureByText(fileName, text)
        return PsiTreeUtil.collectElements(file) { it.firstChild == null }
            .mapNotNull { leaf: PsiElement -> SoundReferenceDetector.candidate(leaf) }
    }

    fun testJava() {
        val found = candidates(
            "A.java", """
            class A {
                void f(Player p) {
                    p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
                    p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_ANVIL_PLACE, 1f, 1f);
                    p.playSound(p.getLocation(), SoundEvents.ZOMBIE_HURT, 1f, 1f);
                    p.playSound(p.getLocation(), "minecraft:entity.experience_orb.pickup", 1f, 1f);
                    String notASound = "hello.world";
                    Object other = Material.STONE_BRICKS;
                }
            }
            """.trimIndent()
        )
        assertEquals(
            listOf(
                Candidate.Constant("ENTITY_PLAYER_LEVELUP", false),
                Candidate.Constant("BLOCK_ANVIL_PLACE", false),
                Candidate.Constant("ZOMBIE_HURT", true),
                Candidate.Key("entity.experience_orb.pickup"),
            ),
            found,
        )
    }

    fun testKotlin() {
        val found = candidates(
            "A.kt", """
            fun f(level: Level) {
                level.playSound(null, pos, SoundEvents.ZOMBIE_HURT, SoundSource.HOSTILE, 1f, 1f)
                val key = Key.key("entity.ender_dragon.growl")
                val tpl = "entity.${'$'}name.hurt"
                val notSound = "hello.world"
            }
            """.trimIndent()
        )
        assertEquals(
            listOf(Candidate.Constant("ZOMBIE_HURT", true), Candidate.Key("entity.ender_dragon.growl")),
            found,
        )
    }

    fun testInlayOffsetsForSeveralSoundsOnOneLine() {
        val text = "val a = listOf(Sound.ENTITY_PLAYER_LEVELUP, \"minecraft:block.anvil.place\", Sound.UI_BUTTON_CLICK)"
        val file = myFixture.configureByText("B.kt", text)
        val offsets = PsiTreeUtil.collectElements(file) { it.firstChild == null }
            .filter { SoundReferenceDetector.candidate(it) != null }
            .map { SoundReferenceDetector.inlayOffset(it) }
        assertEquals(
            listOf(
                text.indexOf("LEVELUP") + "LEVELUP".length,
                text.indexOf("place\"") + "place\"".length,
                text.indexOf("CLICK") + "CLICK".length,
            ),
            offsets,
        )
    }
}
