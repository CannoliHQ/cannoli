package dev.cannoli.igm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CheatSessionTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun manager() = CheatManager(tmp.root.absolutePath, "snes", "Game")

    private fun cheatFile(name: String, vararg cheats: CheatEntry): CheatFile {
        val dir = File(tmp.root, "Cheats/snes/Game").apply { mkdirs() }
        return CheatFile(File(dir, name).apply { writeText("") }, cheats.toList())
    }

    private fun emu(desc: String, code: String) = CheatEntry(desc = desc, code = code)
    private fun retro(desc: String) = CheatEntry(desc = desc, handler = CheatEntry.HANDLER_RETRO, address = 100, value = 9)

    @Test
    fun rowsFlattenFilesInOrderWithLabels() {
        val s = CheatSession(
            manager(),
            listOf(
                cheatFile("a.cht", emu("One", "AAAA"), CheatEntry()),
                cheatFile("b.cht", emu("Two", "BBBB")),
            ),
            hasSystemRam = true
        )
        assertEquals(3, s.rows.size)
        assertEquals("One", s.rows[0].label)
        assertEquals("Cheat 2", s.rows[1].label)
        assertEquals(1, s.rows[2].fileIndex)
        assertEquals(0, s.rows[2].cheatIndex)
    }

    @Test
    fun retroCheatsUnsupportedWithoutSystemRam() {
        val s = CheatSession(manager(), listOf(cheatFile("a.cht", retro("R"), emu("E", "AAAA"))), hasSystemRam = false)
        assertFalse(s.rows[0].supported)
        assertTrue(s.rows[1].supported)
        assertEquals(1, s.firstSupportedIndex())
        assertFalse(s.toggle(0))
        assertTrue(s.toggle(1))
    }

    @Test
    fun allCheatsStartDisabled() {
        val m = manager()
        m.saveRemembered(mapOf("a.cht" to setOf(0)))
        val s = CheatSession(m, listOf(cheatFile("a.cht", emu("E", "AAAA"))), hasSystemRam = true)
        assertFalse(s.isEnabled(s.rows[0]))
        assertTrue(s.hasRemembered())
    }

    @Test
    fun toggleEnablesAndPersists() {
        val m = manager()
        val s = CheatSession(m, listOf(cheatFile("a.cht", emu("E", "AAAA"), emu("F", "BBBB"))), hasSystemRam = true)
        assertTrue(s.toggle(1))
        assertTrue(s.isEnabled(s.rows[1]))
        assertEquals(setOf(1), m.loadRemembered()["a.cht"])
    }

    @Test
    fun disablingEverythingLeavesStoreIntact() {
        val m = manager()
        val s = CheatSession(m, listOf(cheatFile("a.cht", emu("E", "AAAA"))), hasSystemRam = true)
        s.toggle(0)
        assertEquals(setOf(0), m.loadRemembered()["a.cht"])
        s.toggle(0)
        assertFalse(s.isEnabled(s.rows[0]))
        assertEquals(setOf(0), m.loadRemembered()["a.cht"])
    }

    @Test
    fun restoreEnablesRememberedValidSupportedIndexes() {
        val m = manager()
        m.saveRemembered(mapOf("a.cht" to setOf(0, 1, 7), "b.cht" to setOf(0)))
        val s = CheatSession(
            m,
            listOf(
                cheatFile("a.cht", emu("E", "AAAA"), retro("R")),
                cheatFile("b.cht", emu("G", "CCCC")),
            ),
            hasSystemRam = false
        )
        val restored = s.restoreLastSession()
        assertEquals(2, restored)
        assertTrue(s.isEnabled(s.rows[0]))
        assertFalse(s.isEnabled(s.rows[1]))
        assertTrue(s.isEnabled(s.rows[2]))
    }

    @Test
    fun hasRememberedFalseWhenIndexesStale() {
        val m = manager()
        m.saveRemembered(mapOf("a.cht" to setOf(9)))
        val s = CheatSession(m, listOf(cheatFile("a.cht", emu("E", "AAAA"))), hasSystemRam = true)
        assertFalse(s.hasRemembered())
        assertEquals(0, s.restoreLastSession())
    }

    @Test
    fun emuCodesOnlyEnabledEmuWithCode() {
        val s = CheatSession(
            manager(),
            listOf(cheatFile("a.cht", emu("E", "AAAA"), retro("R"), emu("Blank", ""), emu("F", "BBBB"))),
            hasSystemRam = true
        )
        s.toggle(0); s.toggle(1); s.toggle(2); s.toggle(3)
        assertEquals(listOf("AAAA", "BBBB"), s.emuCodes())
    }

    @Test
    fun retroTableCoversAllRowsWithEnabledFlags() {
        val s = CheatSession(
            manager(),
            listOf(cheatFile("a.cht", emu("E", "AAAA"), retro("R"))),
            hasSystemRam = true
        )
        s.toggle(1)
        val t = s.retroTable()
        assertEquals(2 * CheatSession.STRIDE, t.size)
        assertEquals(0L, t[0])
        assertEquals(0L, t[1])
        assertEquals(1L, t[CheatSession.STRIDE + 0])
        assertEquals(1L, t[CheatSession.STRIDE + 1])
        assertEquals(100L, t[CheatSession.STRIDE + 2])
        assertEquals(9L, t[CheatSession.STRIDE + 4])
    }

    @Test
    fun anyEnabledReflectsState() {
        val s = CheatSession(manager(), listOf(cheatFile("a.cht", emu("E", "AAAA"))), hasSystemRam = true)
        assertFalse(s.anyEnabled())
        s.toggle(0)
        assertTrue(s.anyEnabled())
    }
}
