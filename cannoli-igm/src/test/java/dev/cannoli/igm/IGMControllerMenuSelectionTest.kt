package dev.cannoli.igm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val DPAD_DOWN = 20

class IGMControllerMenuSelectionTest {

    private fun menu(c: IGMController) = c.currentScreen as IGMScreen.Menu

    @Test fun `a first open starts at the top`() {
        val c = testController(FakeEmulatorBridge())
        c.openMenu()
        assertEquals(0, menu(c).selectedIndex)
    }

    // Reopening returns to the row the player left. Before this, the menu snapped back to Resume,
    // which also showed as a visible jump once the composition started surviving a close.
    @Test fun `reopening the menu keeps the last selection`() {
        val c = testController(FakeEmulatorBridge())
        c.openMenu()
        c.handleKeyDown(DPAD_DOWN)
        c.handleKeyDown(DPAD_DOWN)
        val before = menu(c).selectedIndex
        assertTrue("the fixture must have enough rows to move", before > 0)

        c.closeMenu()
        c.openMenu()

        assertEquals(before, menu(c).selectedIndex)
    }

    @Test fun `the selection is remembered from underneath a submenu`() {
        val c = testController(FakeEmulatorBridge())
        c.openMenu()
        c.handleKeyDown(DPAD_DOWN)
        val before = menu(c).selectedIndex

        c.push(IGMScreen.Shortcuts())
        c.closeMenu()
        c.openMenu()

        assertEquals(before, menu(c).selectedIndex)
    }
}
