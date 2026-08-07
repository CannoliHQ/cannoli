package dev.cannoli.igm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InGameMenuOptionsCheatsTest {

    @Test
    fun cheatsEntryAppearsBetweenGuideAndSettings() {
        val opts = InGameMenuOptions(
            hasDiscs = false,
            hasAchievements = false,
            hasGuides = true,
            hasCheats = true,
        )
        val gi = opts.actions.indexOf(IgmMenuAction.GUIDE)
        val ci = opts.actions.indexOf(IgmMenuAction.CHEATS)
        val si = opts.actions.indexOf(IgmMenuAction.SETTINGS)
        assertTrue(gi in 0 until ci)
        assertTrue(ci in (gi + 1) until si)
        assertEquals(ci, opts.cheatsIndex)
    }

    @Test
    fun cheatsHiddenByDefault() {
        val opts = InGameMenuOptions(hasDiscs = false)
        assertEquals(-1, opts.actions.indexOf(IgmMenuAction.CHEATS))
        assertEquals(-1, opts.cheatsIndex)
    }
}
