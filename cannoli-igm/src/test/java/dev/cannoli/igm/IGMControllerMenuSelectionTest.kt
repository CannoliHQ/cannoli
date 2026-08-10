package dev.cannoli.igm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val DPAD_DOWN = 20
private const val CONFIRM = 96

class IGMControllerMenuSelectionTest {

    private fun menu(c: IGMController) = c.currentScreen as IGMScreen.Menu

    @Test fun `a first open starts at the top`() {
        val c = testController(FakeRetroArchBridge())
        c.openMenu()
        assertEquals(0, menu(c).selectedIndex)
    }

    @Test fun `reopening the menu keeps the last selection`() {
        val c = testController(FakeRetroArchBridge())
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
        val c = testController(FakeRetroArchBridge())
        c.openMenu()
        c.handleKeyDown(DPAD_DOWN)
        val before = menu(c).selectedIndex

        c.push(IGMScreen.GuidePicker())
        c.closeMenu()
        c.openMenu()

        assertEquals(before, menu(c).selectedIndex)
    }

    @Test fun `the screen stack survives a trip through the native menu`() {
        val bridge = FakeRetroArchBridge()
        val c = testController(bridge)
        c.openMenu()
        val settings = IGMScreen.ProviderSettings(selectedIndex = 3, title = "Settings")
        c.push(settings)

        c.suspendForNativeMenu()

        assertEquals(1, bridge.nativeMenuOpened)
        assertEquals(settings, c.currentScreen)
    }

    @Test fun `closing the native menu brings the IGM back`() {
        val bridge = FakeRetroArchBridge()
        val c = testController(bridge)
        var backUp = 0
        c.onNativeMenuClosed = { backUp++ }
        c.openMenu()
        c.push(IGMScreen.ProviderSettings(selectedIndex = 3, title = "Settings"))
        c.suspendForNativeMenu()

        bridge.closeNativeMenu()

        assertEquals(1, backUp)
    }

    @Test fun `the remembered row survives the native menu round trip`() {
        val bridge = FakeRetroArchBridge()
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

    private class HardcoreBridge : FakeRetroArchBridge() {
        override val savestatesAllowed = false
    }

    @Test fun `save and load rows are absent under hardcore`() {
        val opts = testController(HardcoreBridge()).buildMenuOptions()
        assertEquals(-1, opts.saveStateIndex)
        assertEquals(-1, opts.loadStateIndex)
        assertTrue(opts.resumeIndex >= 0)
        assertTrue(opts.resetIndex >= 0)
        assertTrue(opts.quitIndex >= 0)
    }

    @Test fun `save and load rows are present otherwise`() {
        val opts = testController(FakeRetroArchBridge()).buildMenuOptions()
        assertTrue(opts.saveStateIndex >= 0)
        assertTrue(opts.loadStateIndex >= 0)
    }

    @Test fun `a hardcore menu cannot select a hidden save row`() {
        val bridge = HardcoreBridge()
        val c = testController(bridge)
        c.openMenu()
        val opts = c.buildMenuOptions()
        // Confirming every row in turn, so the empty slot lists below mean no row could reach a
        // state rather than that nothing was ever pressed.
        for (i in opts.actions.indices) {
            assertEquals(i, menu(c).selectedIndex)
            assertTrue(opts.actionAt(i) != IgmMenuAction.SAVE_STATE)
            assertTrue(opts.actionAt(i) != IgmMenuAction.LOAD_STATE)
            c.handleKeyDown(CONFIRM)
            c.handleKeyDown(DPAD_DOWN)
        }
        assertTrue(bridge.savedSlots.isEmpty())
        assertTrue(bridge.loadedSlots.isEmpty())
    }
}
