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
import java.util.concurrent.Executor

class CheatManagerTest {
    @get:Rule val tmp = TemporaryFolder()

    /** Writes land before the call returns, so every assertion below stays synchronous. */
    private val direct = Executor { it.run() }

    private fun manager(tag: String, game: String) =
        CheatManager(tmp.root.absolutePath, tag, game, writer = direct)

    private fun stateFile(tag: String, game: String): File =
        File(tmp.root, "Config/State/Cheats/$tag/$game.ini")

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
    fun takesTheAlphabeticallyFirstChtCaseInsensitive() {
        val dir = cheatsDir("snes", "Game")
        writeCht(dir, "b.cht", 1)
        writeCht(dir, "A.CHT", 2)
        File(dir, "notes.txt").writeText("x")

        val file = manager("snes", "Game").findCheatFile()

        assertEquals("A.CHT", file?.file?.name)
        assertEquals(2, file?.cheats?.size)
    }

    @Test
    fun skipsPastAFileWithNoParsedCheats() {
        val dir = cheatsDir("snes", "Game")
        File(dir, "empty.cht").writeText("not a cht file at all")
        writeCht(dir, "good.cht", 1)

        val file = manager("snes", "Game").findCheatFile()

        assertEquals("good.cht", file?.file?.name)
    }

    @Test
    fun nothingWhenDirMissing() {
        assertNull(manager("snes", "Nope").findCheatFile())
    }

    @Test
    fun lastUsedRoundTrips() {
        val m = manager("snes", "Game")
        m.saveLastUsed("a.cht", setOf("aaaa1111", "bbbb2222"))

        val loaded = m.loadLastUsed()

        assertEquals("a.cht", loaded?.fileName)
        assertEquals(setOf("aaaa1111", "bbbb2222"), loaded?.hashes)
        assertTrue(stateFile("snes", "Game").exists())
    }

    @Test
    fun theFileNameAndTheHashesAreSeparateKeys() {
        manager("snes", "Game").saveLastUsed("Zelda: OoT.cht", setOf("1111", "2222"))

        val text = stateFile("snes", "Game").readText()

        assertTrue(text.contains("[last_used]"))
        assertTrue(text.contains("file=Zelda: OoT.cht"))
        assertTrue(text.contains("hashes=1111,2222"))
        assertEquals("Zelda: OoT.cht", manager("snes", "Game").loadLastUsed()?.fileName)
    }

    @Test
    fun aGameWithNoFileOfItsOwnHasNoLastUsed() {
        manager("snes", "GameOne").saveLastUsed("a.cht", setOf("1111"))

        assertNull(manager("snes", "GameTwo").loadLastUsed())
        assertNull(manager("nes", "GameOne").loadLastUsed())
    }

    @Test
    fun eachGameKeepsItsOwnFile() {
        val m1 = manager("snes", "GameOne")
        val m2 = manager("snes", "GameTwo")
        m1.saveLastUsed("a.cht", setOf("1111"))
        m2.saveLastUsed("b.cht", setOf("2222"))

        assertEquals("a.cht", m1.loadLastUsed()?.fileName)
        assertEquals(setOf("1111"), m1.loadLastUsed()?.hashes)
        assertEquals("b.cht", m2.loadLastUsed()?.fileName)
        assertEquals(setOf("2222"), m2.loadLastUsed()?.hashes)
        assertTrue(stateFile("snes", "GameOne").exists())
        assertTrue(stateFile("snes", "GameTwo").exists())
    }

    @Test
    fun savingTouchesOnlyThisGamesFile() {
        val other = manager("nes", "Other")
        other.saveLastUsed("x.cht", setOf("9999"))
        val before = stateFile("nes", "Other").readText()
        val m = manager("snes", "Game")

        m.saveLastUsed("a.cht", setOf("1111"))
        m.saveLastUsed("b.cht", setOf("2222"))

        assertEquals("b.cht", m.loadLastUsed()?.fileName)
        assertEquals(setOf("2222"), m.loadLastUsed()?.hashes)
        assertEquals(before, stateFile("nes", "Other").readText())
        assertEquals("x.cht", manager("nes", "Other").loadLastUsed()?.fileName)
    }

    @Test
    fun savingAnswersFromMemoryBeforeTheWriteRuns() {
        val queued = mutableListOf<Runnable>()
        val m = CheatManager(tmp.root.absolutePath, "snes", "Game", writer = { queued += it })
        val state = stateFile("snes", "Game")

        m.saveLastUsed("a.cht", setOf("1111"))

        assertEquals("a.cht", m.loadLastUsed()?.fileName)
        assertFalse("the calling thread must not touch the disk", state.exists())

        queued.forEach { it.run() }

        assertEquals("a.cht", manager("snes", "Game").loadLastUsed()?.fileName)
    }

    @Test
    fun aQueuedWriteCarriesTheContentItWasHanded() {
        val queued = mutableListOf<Runnable>()
        val m = CheatManager(tmp.root.absolutePath, "snes", "Game", writer = { queued += it })

        m.saveLastUsed("a.cht", setOf("1111"))
        m.saveLastUsed("b.cht", setOf("2222"))

        queued[0].run()
        assertEquals("a.cht", manager("snes", "Game").loadLastUsed()?.fileName)

        queued[1].run()
        assertEquals("b.cht", manager("snes", "Game").loadLastUsed()?.fileName)
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
