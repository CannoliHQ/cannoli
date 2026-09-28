package dev.cannoli.scorza.ui.screens

import dev.cannoli.scorza.romm.RommFile
import dev.cannoli.scorza.romm.RommGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RommVersionEntriesTest {

    private fun game(id: Int, fsName: String, files: List<RommFile> = emptyList()) =
        RommGame(id, 1, "Game $id", fsName, 0L, null, null, emptyList(), emptyList(), null, files, groupKey = 1)

    private val base = RommFile("Tecmo Super Bowl (USA).nes", 1, null, null, null, id = 1832, category = "game", isTopLevel = true)
    private val hack26 = RommFile("Tecmo Super Bowl 2026.nes", 1, null, null, null, id = 1834, subDir = "hacks", category = "hack")
    private val hack25 = RommFile("Tecmo Super Bowl 2025.nes", 1, null, null, null, id = 1833, subDir = "hacks", category = "hack")
    private val tecmo = game(1343, "Tecmo Super Bowl", listOf(base, hack26, hack25))

    @Test fun `a hacks entry lists itself then one row per hack by name`() {
        val rows = rommVersionEntries(tecmo, listOf(tecmo), presentIds = emptySet(), presentNames = emptySet())
        assertEquals(listOf("Tecmo Super Bowl", "Tecmo Super Bowl 2025", "Tecmo Super Bowl 2026"), rows.map { it.label })
        assertNull(rows[0].hackFile)
        assertEquals(listOf(1833, 1834), rows.drop(1).map { it.hackFile?.id })
        assertEquals(listOf(true, false, false), rows.map { it.isPrimary })
        rows.forEach { assertEquals(tecmo, it.game) }
    }

    @Test fun `siblings keep their order and hacks follow them`() {
        val sibling = game(1400, "Tecmo Super Bowl (Japan).nes")
        val rows = rommVersionEntries(tecmo, listOf(sibling, tecmo), presentIds = emptySet(), presentNames = emptySet())
        assertEquals(
            listOf("Tecmo Super Bowl", "Tecmo Super Bowl (Japan)", "Tecmo Super Bowl 2025", "Tecmo Super Bowl 2026"),
            rows.map { it.label },
        )
    }

    @Test fun `a hack is present when its file name is in the platform library`() {
        val rows = rommVersionEntries(tecmo, listOf(tecmo), presentIds = setOf(1343), presentNames = setOf("tecmo super bowl 2025.nes"))
        assertEquals(listOf(true, true, false), rows.map { it.present })
    }

    @Test fun `the base row keeps the linked-id marking`() {
        val rows = rommVersionEntries(tecmo, listOf(tecmo), presentIds = emptySet(), presentNames = setOf("tecmo super bowl"))
        assertEquals(false, rows[0].present)
    }

    @Test fun `row count opens the picker only with more than one row`() {
        assertEquals(1, rommVersionRowCount(1, game(1, "plain.nes")))
        assertEquals(2, rommVersionRowCount(2, game(1, "plain.nes")))
        assertEquals(3, rommVersionRowCount(1, tecmo))
    }
}
