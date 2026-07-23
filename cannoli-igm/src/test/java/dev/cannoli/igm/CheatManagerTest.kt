package dev.cannoli.igm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CheatManagerTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun cheatsDir(tag: String, game: String): File =
        File(tmp.root, "Cheats/$tag/$game").apply { mkdirs() }

    private fun writeCht(dir: File, name: String, count: Int): File =
        File(dir, name).apply {
            val body = StringBuilder("cheats = $count\n")
            for (i in 0 until count) {
                body.append("cheat${i}_desc = \"Cheat $i\"\n")
                body.append("cheat${i}_code = \"AAA$i\"\n")
            }
            writeText(body.toString())
        }

    @Test
    fun findsAndSortsChtFilesCaseInsensitive() {
        val dir = cheatsDir("snes", "Game")
        writeCht(dir, "b.cht", 1)
        writeCht(dir, "A.CHT", 2)
        File(dir, "notes.txt").writeText("x")

        val files = CheatManager(tmp.root.absolutePath, "snes", "Game").findCheatFiles()

        assertEquals(listOf("A.CHT", "b.cht"), files.map { it.file.name })
        assertEquals(2, files[0].cheats.size)
        assertEquals(1, files[1].cheats.size)
    }

    @Test
    fun ignoresFilesWithNoParsedCheats() {
        val dir = cheatsDir("snes", "Game")
        File(dir, "empty.cht").writeText("not a cht file at all")
        writeCht(dir, "good.cht", 1)

        val files = CheatManager(tmp.root.absolutePath, "snes", "Game").findCheatFiles()

        assertEquals(listOf("good.cht"), files.map { it.file.name })
    }

    @Test
    fun emptyWhenDirMissing() {
        val files = CheatManager(tmp.root.absolutePath, "snes", "Nope").findCheatFiles()
        assertEquals(emptyList<CheatFile>(), files)
    }

    @Test
    fun rememberedSetsRoundTrip() {
        val m = CheatManager(tmp.root.absolutePath, "snes", "Game")
        m.saveRemembered(mapOf("a.cht" to setOf(0, 2), "b.cht" to emptySet()))

        val loaded = m.loadRemembered()

        assertEquals(setOf(0, 2), loaded["a.cht"])
        assertEquals(null, loaded["b.cht"])
        assertTrue(File(tmp.root, "Config/State/cheat_state.ini").exists())
    }

    @Test
    fun rememberedSetsAreScopedPerGame() {
        val m1 = CheatManager(tmp.root.absolutePath, "snes", "GameOne")
        val m2 = CheatManager(tmp.root.absolutePath, "snes", "GameTwo")
        m1.saveRemembered(mapOf("a.cht" to setOf(1)))
        m2.saveRemembered(mapOf("a.cht" to setOf(3)))

        assertEquals(setOf(1), m1.loadRemembered()["a.cht"])
        assertEquals(setOf(3), m2.loadRemembered()["a.cht"])
    }

    @Test
    fun saveReplacesOnlyThisGamesKeys() {
        val other = CheatManager(tmp.root.absolutePath, "nes", "Other")
        other.saveRemembered(mapOf("x.cht" to setOf(5)))
        val m = CheatManager(tmp.root.absolutePath, "snes", "Game")
        m.saveRemembered(mapOf("a.cht" to setOf(0)))
        m.saveRemembered(mapOf("b.cht" to setOf(1)))

        assertEquals(null, m.loadRemembered()["a.cht"])
        assertEquals(setOf(1), m.loadRemembered()["b.cht"])
        assertEquals(setOf(5), other.loadRemembered()["x.cht"])
    }
}
