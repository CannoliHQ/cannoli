package dev.cannoli.igm

import dev.cannoli.core.SaveSlotStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

private const val MENU = 82
private const val BACK = 97
private const val WEST = 99
private const val NORTH = 100

class IGMControllerSlotActionsTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun store() = SaveSlotStore(File(folder.root, "Game.state").absolutePath)

    private fun menu(c: IGMController) = c.currentScreen as IGMScreen.Menu

    /** Opens on the Save State row with [slot] selected, which is where the legend offers delete. */
    private fun openOnSlotRow(c: IGMController, slot: Int) {
        c.selectedSlotIndex.intValue = slot
        c.openMenu()
        c.replaceTop(IGMScreen.Menu(selectedIndex = c.buildMenuOptions().saveStateIndex))
    }

    @Test fun `deleting a slot asks first and then removes the file`() {
        val slots = store()
        File(slots.statePath(1)).writeText("state")
        val c = testController(FakeRetroArchBridge(), slots = slots)
        openOnSlotRow(c, 1)

        c.handleKeyDown(WEST)
        assertTrue(menu(c).confirmDeleteSlot)

        c.handleKeyDown(NORTH)
        assertFalse(menu(c).confirmDeleteSlot)
        assertFalse(File(slots.statePath(1)).exists())
    }

    @Test fun `backing out of the confirmation keeps the state`() {
        val slots = store()
        File(slots.statePath(1)).writeText("state")
        val c = testController(FakeRetroArchBridge(), slots = slots)
        openOnSlotRow(c, 1)

        c.handleKeyDown(WEST)
        c.handleKeyDown(BACK)

        assertFalse(menu(c).confirmDeleteSlot)
        assertTrue(File(slots.statePath(1)).exists())
    }

    @Test fun `a row with no polaroid beside it offers no delete`() {
        val slots = store()
        File(slots.statePath(1)).writeText("state")
        val c = testController(FakeRetroArchBridge(), slots = slots)
        openOnSlotRow(c, 1)
        c.replaceTop(IGMScreen.Menu(selectedIndex = c.buildMenuOptions().resumeIndex))

        c.handleKeyDown(WEST)

        assertFalse("the legend does not offer it here", menu(c).confirmDeleteSlot)
    }

    @Test fun `an empty slot has nothing to delete`() {
        val c = testController(FakeRetroArchBridge(), slots = store())
        openOnSlotRow(c, 1)

        c.handleKeyDown(WEST)

        assertFalse(menu(c).confirmDeleteSlot)
    }

    @Test fun `undo puts back the state a save overwrote`() {
        val slots = store()
        File(slots.statePath(1)).writeText("state")
        val bridge = FakeRetroArchBridge()
        val c = testController(bridge, slots = slots)
        openOnSlotRow(c, 1)

        c.saveState()
        assertEquals(UndoAction.SAVE, c.undoAction.value)

        c.handleKeyDown(NORTH)
        assertEquals(1, bridge.undoneSaves)
        assertNull("one undo per save, or the legend outlives what it undoes", c.undoAction.value)
    }

    // RetroArch fills the undo buffer with what the write displaces, so there is nothing to put
    // back and the legend would be a lie.
    @Test fun `saving into an empty slot offers no undo`() {
        val c = testController(FakeRetroArchBridge(), slots = store())
        openOnSlotRow(c, 1)

        c.saveState()

        assertNull(c.undoAction.value)
    }

    @Test fun `undo puts back the state a load replaced`() {
        val bridge = FakeRetroArchBridge()
        val c = testController(bridge, slots = store())
        openOnSlotRow(c, 1)

        c.loadState()
        assertEquals(UndoAction.LOAD, c.undoAction.value)

        c.handleKeyDown(NORTH)
        assertEquals(1, bridge.undoneLoads)
    }

    @Test fun `north does nothing while there is nothing to undo`() {
        val bridge = FakeRetroArchBridge()
        val c = testController(bridge, slots = store())
        var closed = 0
        c.onClose = { closed++ }
        openOnSlotRow(c, 1)

        c.handleKeyDown(NORTH)

        assertEquals(0, bridge.undoneSaves)
        assertEquals(0, bridge.undoneLoads)
        assertEquals(0, closed)
    }

    @Test fun `the menu closes on the button that opened it`() {
        val c = testController(FakeRetroArchBridge(), slots = store())
        var closed = 0
        c.onClose = { closed++ }
        c.openMenu()

        c.handleKeyDown(MENU)

        assertEquals(1, closed)
    }

    @Test fun `the menu closes on whatever button the device calls menu`() {
        val c = testController(FakeRetroArchBridge(), slots = store())
        c.setInputMapping(
            IgmInputMapping(
                buttonKeycodes = mapOf(CanonicalButton.BTN_MENU to listOf(109)),
                menuConfirm = CanonicalButton.BTN_SOUTH,
                menuBack = CanonicalButton.BTN_EAST,
            )
        )
        var closed = 0
        c.onClose = { closed++ }
        c.openMenu()

        c.handleKeyDown(109)

        assertEquals(1, closed)
    }
}
