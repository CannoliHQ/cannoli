package dev.cannoli.igm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val DPAD_LEFT = 21
private const val DPAD_RIGHT = 22

class IGMControllerDiscTest {

    private fun twoDiscBridge() = FakeRetroArchBridge().apply {
        discs = 2
        disc = 0
    }

    private fun onDiscRow(c: IGMController) {
        val idx = c.buildMenuOptions().switchDiscIndex
        assertTrue("the disc row must exist", idx >= 0)
        c.replaceTop((c.currentScreen as IGMScreen.Menu).copy(selectedIndex = idx))
    }

    @Test fun `a single disc game has no disc row`() {
        val c = testController(FakeRetroArchBridge().apply { discs = 1 })
        c.openMenu()
        assertFalse(c.buildMenuOptions().actions.contains(IgmMenuAction.SWITCH_DISC))
    }

    @Test fun `a multi disc game gets a disc row`() {
        val c = testController(twoDiscBridge())
        c.openMenu()
        assertTrue(c.buildMenuOptions().actions.contains(IgmMenuAction.SWITCH_DISC))
    }

    @Test fun `right cycles to the next disc`() {
        val bridge = twoDiscBridge()
        val c = testController(bridge)
        c.openMenu()
        onDiscRow(c)

        c.handleKeyDown(DPAD_RIGHT)

        assertEquals(1, bridge.disc)
        assertEquals(1, c.currentDiskIndex.intValue)
    }

    @Test fun `right wraps past the last disc`() {
        val bridge = twoDiscBridge().apply { disc = 1 }
        val c = testController(bridge)
        c.openMenu()
        onDiscRow(c)

        c.handleKeyDown(DPAD_RIGHT)

        assertEquals(0, bridge.disc)
    }

    @Test fun `left wraps below the first disc`() {
        val bridge = twoDiscBridge()
        val c = testController(bridge)
        c.openMenu()
        onDiscRow(c)

        c.handleKeyDown(DPAD_LEFT)

        assertEquals(1, bridge.disc)
    }

    // Left and right cycle the save slot everywhere else, which is what they did before the disc
    // row existed.
    @Test fun `left and right still change the save slot off the disc row`() {
        val bridge = twoDiscBridge()
        val c = testController(bridge)
        c.openMenu()

        c.handleKeyDown(DPAD_RIGHT)

        assertEquals("the disc must not move", 0, bridge.disc)
        assertEquals(1, c.selectedSlotIndex.intValue)
    }

    // The row reads its text from string resources, so what the options carry is the index.
    @Test fun `the menu carries the current disc index`() {
        val bridge = twoDiscBridge().apply { disc = 1 }
        val c = testController(bridge)
        c.openMenu()
        assertEquals(1, c.buildMenuOptions().discIndex)
    }

    @Test fun `the carried index follows a cycle`() {
        val c = testController(twoDiscBridge())
        c.openMenu()
        onDiscRow(c)

        c.handleKeyDown(DPAD_RIGHT)

        assertEquals(1, c.buildMenuOptions().discIndex)
    }
}
