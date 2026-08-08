package dev.cannoli.igm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CheatSessionTest {
    @get:Rule val tmp = TemporaryFolder()

    /** Writes land before the call returns, so every assertion below stays synchronous. */
    private fun manager() =
        CheatManager(tmp.root.absolutePath, "snes", "Game", writer = { it.run() })

    private fun cheatFile(name: String, vararg cheats: CheatEntry): CheatFile {
        val dir = File(tmp.root, "Cheats/snes/Game").apply { mkdirs() }
        return CheatFile(File(dir, name).apply { writeText("") }, cheats.toList())
    }

    private fun emu(desc: String, code: String) = CheatEntry(desc = desc, code = code)
    private fun retro(desc: String) =
        CheatEntry(desc = desc, handler = CheatEntry.HANDLER_RETRO, address = 100, value = 9)

    /** What the bridge reports back after a load: same order, all disabled, given support. */
    private fun observed(file: CheatFile, supported: (Int) -> Boolean = { true }) =
        file.cheats.mapIndexed { i, c ->
            RetroArchBridge.CheatRow(i, c.desc, c.code, enabled = false, supported = supported(i))
        }

    private fun session(
        file: CheatFile,
        observed: List<RetroArchBridge.CheatRow> = observed(file),
        manager: CheatManager = manager(),
    ) = CheatSession(manager, file, observed)

    @Test
    fun rowsFollowTheFileOrderWithLabels() {
        val f = cheatFile("a.cht", emu("One", "AAAA"), CheatEntry(), emu("Two", "BBBB"))
        val s = session(f)
        assertEquals(3, s.rows.size)
        assertEquals("One", s.rows[0].label)
        assertEquals("Cheat 2", s.rows[1].label)
        assertEquals(2, s.rows[2].cheatIndex)
    }

    @Test
    fun raIndexesComeFromTheObservedRowsNotThePosition() {
        val f = cheatFile("a.cht", emu("One", "AAAA"), emu("Two", "BBBB"))
        val reordered = listOf(
            RetroArchBridge.CheatRow(0, "Two", "BBBB", enabled = false, supported = true),
            RetroArchBridge.CheatRow(1, "One", "AAAA", enabled = false, supported = true),
        )
        val s = session(f, reordered)
        assertEquals(1, s.rows[0].raIndex)
        assertEquals(0, s.rows[1].raIndex)
    }

    @Test
    fun duplicateIdentitiesMatchInOrder() {
        val f = cheatFile("a.cht", emu("Dup", "AAAA"), emu("Dup", "AAAA"))
        val s = session(f)
        assertEquals(0, s.rows[0].raIndex)
        assertEquals(1, s.rows[1].raIndex)
    }

    @Test
    fun anEntryWithNoObservedMatchIsUnsupported() {
        val f = cheatFile("a.cht", emu("One", "AAAA"), emu("Missing", "ZZZZ"))
        val partial = listOf(
            RetroArchBridge.CheatRow(0, "One", "AAAA", enabled = false, supported = true),
        )
        val s = session(f, partial)
        assertTrue(s.rows[0].supported)
        assertFalse(s.rows[1].supported)
        assertEquals(-1, s.rows[1].raIndex)
        assertNull(s.toggle(1))
    }

    @Test
    fun unsupportedObservedRowsStayUnsupported() {
        val f = cheatFile("a.cht", retro("R"), emu("E", "AAAA"))
        val s = session(f, observed(f) { it != 0 })
        assertFalse(s.rows[0].supported)
        assertTrue(s.rows[1].supported)
        assertEquals(1, s.firstSupportedIndex())
    }

    @Test
    fun everythingStartsDisabled() {
        val m = manager()
        val f = cheatFile("a.cht", emu("E", "AAAA"))
        m.saveLastUsed("a.cht", setOf(CheatIdentity.hash("E", "AAAA")))
        val s = session(f, manager = m)
        assertFalse(s.isEnabled(s.rows[0]))
        assertFalse(s.anyEnabled())
        assertTrue(s.canRestore(m.loadLastUsed()!!.hashes))
    }

    @Test
    fun toggleReturnsTheRowAndPersists() {
        val m = manager()
        val f = cheatFile("a.cht", emu("E", "AAAA"), emu("F", "BBBB"))
        val s = session(f, manager = m)

        val row = s.toggle(1)

        assertEquals(1, row?.raIndex)
        assertTrue(s.isEnabled(s.rows[1]))
        assertTrue(s.anyEnabled())
        assertEquals(setOf(CheatIdentity.hash("F", "BBBB")), m.loadLastUsed()?.hashes)
        assertEquals("a.cht", m.loadLastUsed()?.fileName)
    }

    @Test
    fun disablingEverythingLeavesTheStoreIntact() {
        val m = manager()
        val f = cheatFile("a.cht", emu("E", "AAAA"))
        val s = session(f, manager = m)
        s.toggle(0)
        assertEquals(setOf(CheatIdentity.hash("E", "AAAA")), m.loadLastUsed()?.hashes)

        s.toggle(0)

        assertFalse(s.isEnabled(s.rows[0]))
        assertEquals(setOf(CheatIdentity.hash("E", "AAAA")), m.loadLastUsed()?.hashes)
    }

    @Test
    fun restoreEnablesRememberedSupportedIdentities() {
        val m = manager()
        val f = cheatFile("a.cht", emu("E", "AAAA"), retro("R"), emu("G", "CCCC"))
        val s = session(f, observed(f) { it != 1 }, m)
        val hashes = setOf(
            CheatIdentity.hash("E", "AAAA"),
            CheatIdentity.hash("R", ""),
            CheatIdentity.hash("G", "CCCC"),
            "deadbeef",
        )

        val restored = s.restore(hashes)

        assertEquals(2, restored.size)
        assertTrue(s.isEnabled(s.rows[0]))
        assertFalse(s.isEnabled(s.rows[1]))
        assertTrue(s.isEnabled(s.rows[2]))
    }

    @Test
    fun restoreSkipsWhatIsAlreadyOn() {
        val f = cheatFile("a.cht", emu("E", "AAAA"))
        val s = session(f)
        s.toggle(0)
        assertEquals(0, s.restore(setOf(CheatIdentity.hash("E", "AAAA"))).size)
    }

    @Test
    fun canRestoreIsFalseWhenNothingMatches() {
        val f = cheatFile("a.cht", emu("E", "AAAA"))
        val s = session(f)
        assertFalse(s.canRestore(setOf("deadbeef")))
        assertEquals(0, s.restore(setOf("deadbeef")).size)
    }

    @Test
    fun canRestoreAgreesWithWhatRestoreWouldDo() {
        val f = cheatFile("a.cht", emu("E", "AAAA"), retro("R"))
        val s = session(f, observed(f) { it != 1 })
        val hashes = setOf(CheatIdentity.hash("E", "AAAA"), CheatIdentity.hash("R", ""))
        assertTrue(s.canRestore(hashes))

        assertEquals(1, s.restore(hashes).size)

        assertFalse(s.canRestore(hashes))
        assertEquals(0, s.restore(hashes).size)
    }

    @Test
    fun seedsEnabledStateFromTheObservedRows() {
        val f = cheatFile("a.cht", emu("E", "AAAA"), emu("F", "BBBB"))
        val alreadyOn = listOf(
            RetroArchBridge.CheatRow(0, "E", "AAAA", enabled = false, supported = true),
            RetroArchBridge.CheatRow(1, "F", "BBBB", enabled = true, supported = true),
        )
        val s = session(f, alreadyOn)
        assertFalse(s.isEnabled(s.rows[0]))
        assertTrue(s.isEnabled(s.rows[1]))
        assertTrue(s.anyEnabled())
    }
}
