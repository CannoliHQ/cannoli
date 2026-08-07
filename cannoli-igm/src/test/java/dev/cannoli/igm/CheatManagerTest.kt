package dev.cannoli.igm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
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
    fun lastUsedRoundTrips() {
        val m = CheatManager(tmp.root.absolutePath, "snes", "Game")
        m.saveLastUsed("a.cht", setOf("aaaa1111", "bbbb2222"))

        val loaded = m.loadLastUsed()

        assertEquals("a.cht", loaded?.fileName)
        assertEquals(setOf("aaaa1111", "bbbb2222"), loaded?.hashes)
        assertTrue(File(tmp.root, "Config/State/cheat_state.ini").exists())
    }

    @Test
    fun lastUsedIsScopedPerGame() {
        val m1 = CheatManager(tmp.root.absolutePath, "snes", "GameOne")
        val m2 = CheatManager(tmp.root.absolutePath, "snes", "GameTwo")
        m1.saveLastUsed("a.cht", setOf("1111"))
        m2.saveLastUsed("b.cht", setOf("2222"))

        assertEquals("a.cht", m1.loadLastUsed()?.fileName)
        assertEquals(setOf("1111"), m1.loadLastUsed()?.hashes)
        assertEquals("b.cht", m2.loadLastUsed()?.fileName)
    }

    @Test
    fun savingReplacesThisGamesEntryOnly() {
        val other = CheatManager(tmp.root.absolutePath, "nes", "Other")
        other.saveLastUsed("x.cht", setOf("9999"))
        val m = CheatManager(tmp.root.absolutePath, "snes", "Game")
        m.saveLastUsed("a.cht", setOf("1111"))
        m.saveLastUsed("b.cht", setOf("2222"))

        assertEquals("b.cht", m.loadLastUsed()?.fileName)
        assertEquals(setOf("2222"), m.loadLastUsed()?.hashes)
        assertEquals("x.cht", other.loadLastUsed()?.fileName)
    }

    @Test
    fun v1EntriesAreIgnoredAndCleanedOnSave() {
        val state = File(tmp.root, "Config/State/cheat_state.ini")
        state.parentFile!!.mkdirs()
        state.writeText("[enabled]\nsnes/Game/a.cht=0,2\nnes/Other/x.cht=1\n")
        val m = CheatManager(tmp.root.absolutePath, "snes", "Game")

        assertNull(m.loadLastUsed())

        m.saveLastUsed("a.cht", setOf("1111"))
        val text = state.readText()
        assertFalse(text.contains("snes/Game/a.cht"))
        assertTrue(text.contains("nes/Other/x.cht"))
    }

    @Test
    fun identityHashIsStableAndSeparatesDescFromCode() {
        assertEquals(CheatIdentity.hash("Infinite HP", "AAAA"), CheatIdentity.hash("Infinite HP", "AAAA"))
        assertNotEquals(CheatIdentity.hash("Infinite HP", "AAAA"), CheatIdentity.hash("Infinite", "HPAAAA"))
    }

    @Test
    fun identityHashMatchesPinnedVector() {
        assertEquals("e1f8f72c", CheatIdentity.hash("Infinite Lives", "AAAA-BBBB"))
    }
}
