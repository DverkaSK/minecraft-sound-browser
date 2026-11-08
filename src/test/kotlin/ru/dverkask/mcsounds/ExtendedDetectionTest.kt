package ru.dverkask.mcsounds

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import ru.dverkask.mcsounds.catalog.ProjectSounds
import ru.dverkask.mcsounds.editor.SoundReferenceDetector
import ru.dverkask.mcsounds.editor.SoundReferenceDetector.Candidate
import ru.dverkask.mcsounds.model.SoundCatalog
import ru.dverkask.mcsounds.model.SoundEvent
import ru.dverkask.mcsounds.model.SoundFile

class ExtendedDetectionTest : BasePlatformTestCase() {

    private fun candidatesIn(file: PsiFile): List<Candidate> =
        PsiTreeUtil.collectElements(file) { it.firstChild == null }
            .mapNotNull { leaf: PsiElement -> SoundReferenceDetector.candidate(leaf, resolveDeclarations = true) }

    private fun candidates(fileName: String, text: String): List<Candidate> =
        candidatesIn(myFixture.configureByText(fileName, text))

    fun testSponge() {
        assertEquals(
            listOf(Candidate.Constant("ENTITY_PLAYER_LEVELUP", false)),
            candidates("A.java", "class A { Object s = SoundTypes.ENTITY_PLAYER_LEVELUP; }"),
        )
    }

    fun testJavaStaticImports() {
        val found = candidates(
            "A.java", """
            import static org.bukkit.Sound.ENTITY_PLAYER_LEVELUP;
            import static org.bukkit.Sound.*;
            class A {
                static final int MAX_VALUE_X = 1;
                void f() {
                    play(ENTITY_PLAYER_LEVELUP);
                    play(BLOCK_ANVIL_PLACE);
                    int x = MAX_VALUE_X;
                }
                void play(Object o) {}
            }
            """.trimIndent()
        )
        assertEquals(
            listOf(Candidate.Constant("ENTITY_PLAYER_LEVELUP", false), Candidate.Constant("BLOCK_ANVIL_PLACE", false)),
            found,
        )
    }

    fun testKotlinImport() {
        val found = candidates(
            "A.kt", """
            import org.bukkit.Sound.ENTITY_CAT_AMBIENT
            fun f() = listOf(ENTITY_CAT_AMBIENT)
            """.trimIndent()
        )
        assertEquals(listOf(Candidate.Constant("ENTITY_CAT_AMBIENT", false)), found)
    }

    fun testLegacyBukkitNames() {
        val found = candidates("A.java", "class A { Object[] s = { Sound.LEVEL_UP, Sound.CLICK, Material.CLICK }; }")
        assertEquals(
            listOf(Candidate.Constant("LEVEL_UP", false, legacy = true), Candidate.Constant("CLICK", false, legacy = true)),
            found,
        )
        val levelUp = SoundEvent("entity.player.levelup", listOf(SoundFile("random/levelup")))
        val catalog = SoundCatalog("1.21", listOf(levelUp), emptyMap())
        assertEquals(levelUp, SoundReferenceDetector.resolve(found[0], catalog, project))
    }

    fun testGroovy() {
        val found = candidates(
            "A.groovy", """
            def f(p, loc) {
                p.playSound(loc, Sound.ENTITY_PLAYER_LEVELUP, 1, 1)
                p.playSound(loc, 'minecraft:block.anvil.place', 1, 1)
                p.playSound(loc, "entity.cat.ambient", 1, 1)
            }
            """.trimIndent()
        )
        assertEquals(
            listOf(
                Candidate.Constant("ENTITY_PLAYER_LEVELUP", false),
                Candidate.Key("block.anvil.place"),
                Candidate.Key("entity.cat.ambient"),
            ),
            found,
        )
    }

    fun testJsonDataPack() {
        val found = candidates("advancement.json", """{ "sound": "minecraft:entity.player.levelup", "other": "hello.world" }""")
        assertEquals(listOf(Candidate.Key("entity.player.levelup")), found)
    }

    fun testPlainText() {
        val file = myFixture.configureByText("a.txt", "playsound minecraft:entity.player.levelup master @s\nsay hello.world")
        val found = SoundReferenceDetector.textCandidates(file.text, file)
        assertEquals(listOf(Candidate.Key("entity.player.levelup")), found.map { it.second })
        assertEquals(file.text.indexOf(" master"), found.single().first)
    }

    private fun addModSounds() {
        myFixture.addFileToProject(
            "src/main/resources/assets/mymod/sounds.json",
            """
            {
              "boss.roar": {
                "subtitle": "subtitles.mymod.boss_roar",
                "sounds": ["mymod:boss/roar1", { "name": "minecraft:mob/cat/meow1", "volume": 0.5 }]
              }
            }
            """.trimIndent(),
        )
        myFixture.addFileToProject("src/main/resources/assets/mymod/sounds/boss/roar1.ogg", "OggS")
    }

    fun testProjectSoundsIndex() {
        addModSounds()
        val event = ProjectSounds.getInstance(project).index().find("mymod:boss.roar")
        assertNotNull(event)
        assertEquals("mymod:boss.roar", event!!.displayKey)
        assertEquals(listOf("mymod", "minecraft"), event.files.map { it.namespace })
        assertNotNull(event.files[0].localPath)
        assertNull(event.files[1].localPath)
        assertEquals(event, ProjectSounds.getInstance(project).index().find("boss.roar"))
    }

    fun testModConstantsAndKeys() {
        addModSounds()
        val found = candidates(
            "ModSounds.java", """
            class ModSounds {
                static final Object BOSS_ROAR = register("boss.roar");
                static Object register(String id) { return null; }
            }
            class Use {
                void f() {
                    Object a = ModSounds.BOSS_ROAR;
                    String s = "mymod:boss.roar";
                    String u = "mymod:unknown.sound";
                }
            }
            """.trimIndent()
        )
        assertEquals(
            listOf(
                Candidate.Project("mymod:boss.roar"),
                Candidate.Project("mymod:boss.roar"),
                Candidate.Project("mymod:boss.roar"),
            ),
            found,
        )
    }

    fun testSoundsJsonEntries() {
        addModSounds()
        val vf = myFixture.findFileInTempDir("src/main/resources/assets/mymod/sounds.json")
        val file = myFixture.psiManager.findFile(vf)!!
        val found = candidatesIn(file)
        assertEquals(3, found.size)
        assertEquals(Candidate.Project("mymod:boss.roar"), found[0])
        val roar = found[1] as Candidate.File
        assertEquals("mymod" to "boss/roar1", roar.namespace to roar.path)
        assertNotNull(roar.localPath)
        assertEquals(Candidate.File("minecraft", "mob/cat/meow1", null), found[2])
    }
}
