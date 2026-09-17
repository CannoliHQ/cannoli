package dev.cannoli.igm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val DPAD_DOWN = 20
private const val CONFIRM = 96
private const val BACK = 97

class IGMControllerReassignTest {

    private fun bridgeWith(vararg names: String?) = FakeRetroArchBridge().apply {
        playerSlots = names.mapIndexed { i, name -> PlayerSlot(i, i, name, 0) }
    }

    private fun openReassign(bridge: FakeRetroArchBridge): IGMController {
        val c = testController(bridge)
        c.openMenu()
        val idx = c.buildMenuOptions().reassignIndex
        assertTrue("the reassign row must exist", idx >= 0)
        c.replaceTop((c.currentScreen as IGMScreen.Menu).copy(selectedIndex = idx))
        c.handleKeyDown(CONFIRM)
        return c
    }

    private fun screen(c: IGMController) = c.currentScreen as IGMScreen.ReassignPlayers

    @Test fun `one pad offers no reassign`() {
        val c = testController(bridgeWith("Pad A", null, null, null))
        c.openMenu()
        assertFalse(c.buildMenuOptions().actions.contains(IgmMenuAction.REASSIGN))
    }

    @Test fun `two pads offer reassign and open the screen with nothing marked`() {
        val c = openReassign(bridgeWith("Pad A", "Pad B", null, null))
        assertEquals(0, screen(c).selectedIndex)
        assertNull(screen(c).marked)
    }

    @Test fun `confirm marks a row and confirm on another swaps them`() {
        val bridge = bridgeWith("Pad A", "Pad B", null, null)
        val c = openReassign(bridge)

        c.handleKeyDown(CONFIRM)
        assertEquals(0, screen(c).marked)
        c.handleKeyDown(DPAD_DOWN)
        c.handleKeyDown(CONFIRM)

        assertEquals(listOf(0 to 1), bridge.swaps)
        assertNull(screen(c).marked)
    }

    @Test fun `the rows show the swap without waiting for RetroArch`() {
        val bridge = bridgeWith("Pad A", "Pad B", null, null)
        val c = openReassign(bridge)

        c.handleKeyDown(CONFIRM)
        c.handleKeyDown(DPAD_DOWN)
        c.handleKeyDown(CONFIRM)

        assertEquals(listOf("Pad B", "Pad A", null, null), c.players.value.map { it.name })
    }

    @Test fun `confirm on the marked row clears the mark`() {
        val bridge = bridgeWith("Pad A", "Pad B", null, null)
        val c = openReassign(bridge)
        c.handleKeyDown(CONFIRM)

        c.handleKeyDown(CONFIRM)

        assertNull(screen(c).marked)
        assertTrue(bridge.swaps.isEmpty())
    }

    @Test fun `back clears a mark before it leaves`() {
        val c = openReassign(bridgeWith("Pad A", "Pad B", null, null))
        c.handleKeyDown(CONFIRM)

        c.handleKeyDown(BACK)
        assertNull(screen(c).marked)

        c.handleKeyDown(BACK)
        assertTrue(c.currentScreen is IGMScreen.Menu)
    }

    @Test fun `a row without a pad cannot be marked`() {
        val c = openReassign(bridgeWith("Pad A", "Pad B", null, null))
        repeat(2) { c.handleKeyDown(DPAD_DOWN) }

        c.handleKeyDown(CONFIRM)

        assertNull(screen(c).marked)
    }

    @Test fun `player 1 is never moved onto an empty row`() {
        val bridge = bridgeWith("Pad A", "Pad B", null, null)
        val c = openReassign(bridge)
        c.handleKeyDown(CONFIRM)
        repeat(2) { c.handleKeyDown(DPAD_DOWN) }

        c.handleKeyDown(CONFIRM)

        assertTrue(bridge.swaps.isEmpty())
        assertEquals(0, screen(c).marked)
    }
}
