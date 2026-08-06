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

    // Handing off to RetroArch's own menu used to clear the stack and come back at the root, which
    // showed as the settings row flashing before the root menu landed. The stack stands instead.
    @Test fun `the screen stack survives a trip through the native menu`() {
        val bridge = FakeEmulatorBridge()
        val c = testController(bridge)
        c.openMenu()
        val settings = IGMScreen.ProviderSettings(selectedIndex = 3, title = "Settings")
        c.push(settings)

        c.suspendForNativeMenu()

        assertEquals(1, bridge.nativeMenuOpened)
        assertEquals(settings, c.currentScreen)
    }

    @Test fun `closing the native menu brings the IGM back`() {
        val bridge = FakeEmulatorBridge()
        val c = testController(bridge)
        var backUp = 0
        c.onNativeMenuClosed = { backUp++ }
        c.openMenu()
        c.push(IGMScreen.ProviderSettings(selectedIndex = 3, title = "Settings"))
        c.suspendForNativeMenu()

        bridge.closeNativeMenu()

        assertEquals(1, backUp)
    }

    // The row underneath is still what a later close rewinds to, so the next open from the game
    // lands on Settings rather than the top of the list.
    @Test fun `the remembered row survives the native menu round trip`() {
        val bridge = FakeEmulatorBridge()
        val c = testController(bridge)
        c.openMenu()
        c.handleKeyDown(DPAD_DOWN)
        val before = menu(c).selectedIndex
        assertTrue("the fixture must have enough rows to move", before > 0)

        c.push(IGMScreen.ProviderSettings(title = "Settings"))
        c.suspendForNativeMenu()
        bridge.closeNativeMenu()
        c.closeMenu()
        c.openMenu()

        assertEquals(before, menu(c).selectedIndex)
    }
}
