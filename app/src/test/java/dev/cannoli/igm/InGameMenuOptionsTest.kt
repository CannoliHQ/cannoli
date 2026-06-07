package dev.cannoli.igm

import org.junit.Assert.assertEquals
import org.junit.Test

class InGameMenuOptionsTest {
    @Test fun `ricotta config order`() {
        val o = InGameMenuOptions(hasDiscs = false, discLabel = "Disc 1", hasAchievements = true)
        assertEquals(
            listOf(IgmMenuAction.RESUME, IgmMenuAction.SAVE_STATE, IgmMenuAction.LOAD_STATE,
                IgmMenuAction.ACHIEVEMENTS, IgmMenuAction.SETTINGS, IgmMenuAction.RESET, IgmMenuAction.QUIT),
            o.actions,
        )
        assertEquals(IgmMenuAction.RESET, o.actionAt(5))
        assertEquals(IgmMenuAction.QUIT, o.actionAt(6))
        assertEquals(5, o.resetIndex)
        assertEquals(6, o.quitIndex)
        assertEquals(3, o.achievementsIndex)
    }

    @Test fun `all disabled`() {
        val o = InGameMenuOptions(hasDiscs = false, discLabel = "Disc 1")
        assertEquals(
            listOf(IgmMenuAction.RESUME, IgmMenuAction.SAVE_STATE, IgmMenuAction.LOAD_STATE,
                IgmMenuAction.SETTINGS, IgmMenuAction.RESET, IgmMenuAction.QUIT),
            o.actions,
        )
        assertEquals(4, o.resetIndex)
        assertEquals(-1, o.achievementsIndex)
    }

    @Test fun `full config`() {
        val o = InGameMenuOptions(hasDiscs = true, discLabel = "Disc 1", hasAchievements = true, hasGuides = true, hasReassign = true)
        assertEquals(10, o.actions.size)
        assertEquals(IgmMenuAction.RESET, o.actionAt(o.resetIndex))
        assertEquals(IgmMenuAction.QUIT, o.actionAt(o.quitIndex))
    }
}
