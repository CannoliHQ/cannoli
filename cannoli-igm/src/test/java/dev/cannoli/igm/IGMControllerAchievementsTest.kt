package dev.cannoli.igm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IGMControllerAchievementsTest {
    private fun bridgeWith(achievements: List<AchievementInfo>) = object : FakeRetroArchBridge() {
        override val supportsAchievements = true
        override fun getAchievements() = achievements
    }

    private val sample = listOf(
        AchievementInfo(1, "Alpha", "first", 5, unlocked = true),
        AchievementInfo(2, "Beta", "second", 10, unlocked = false),
    )

    @Test fun optionsIncludeAchievementsOnlyWhenTheFlagIsSet() {
        assertEquals(-1, InGameMenuOptions(hasDiscs = false, hasAchievements = false).achievementsIndex)
        assertTrue(InGameMenuOptions(hasDiscs = false, hasAchievements = true).achievementsIndex >= 0)
    }

    @Test fun menuRowAbsentWithAnEmptySet() {
        val c = testController(bridgeWith(emptyList()))
        c.openMenu()
        assertFalse(c.buildMenuOptions().actions.contains(IgmMenuAction.ACHIEVEMENTS))
    }

    @Test fun menuRowAbsentWhenCoreDoesNotSupportAchievements() {
        val c = testController(FakeRetroArchBridge())
        c.openMenu()
        assertFalse(c.buildMenuOptions().actions.contains(IgmMenuAction.ACHIEVEMENTS))
    }

    @Test fun menuRowPresentWithANonEmptySet() {
        val c = testController(bridgeWith(sample))
        c.openMenu()
        assertTrue(c.buildMenuOptions().actions.contains(IgmMenuAction.ACHIEVEMENTS))
    }

    @Test fun aSetThatArrivesAfterTheFirstOpenShowsOnTheNextOpen() {
        var achievements = emptyList<AchievementInfo>()
        val bridge = object : FakeRetroArchBridge() {
            override val supportsAchievements = true
            override fun getAchievements() = achievements
        }
        val c = testController(bridge)
        c.openMenu()
        assertFalse(c.buildMenuOptions().actions.contains(IgmMenuAction.ACHIEVEMENTS))

        achievements = sample
        c.closeMenu()
        c.openMenu()

        assertTrue(c.buildMenuOptions().actions.contains(IgmMenuAction.ACHIEVEMENTS))
    }

    @Test fun openAchievementsPushesPopulatedScreen() {
        val c = testController(bridgeWith(sample))
        c.openMenu(); c.openAchievements()
        val screen = c.currentScreen
        assertTrue(screen is IGMScreen.Achievements)
        assertEquals(2, (screen as IGMScreen.Achievements).achievements.size)
    }

    @Test fun filterCyclesWithWest() {
        val c = testController(bridgeWith(sample))
        c.openMenu(); c.openAchievements()
        c.handleKeyDown(99)
        assertEquals(1, (c.currentScreen as IGMScreen.Achievements).filter)
    }

    @Test fun southOpensDetailEastReturns() {
        val c = testController(bridgeWith(sample))
        c.openMenu(); c.openAchievements()
        c.handleKeyDown(96)
        assertTrue(c.currentScreen is IGMScreen.AchievementDetail)
        c.handleKeyDown(4)
        assertTrue(c.currentScreen is IGMScreen.Achievements)
    }

    @Test fun filterCyclesWithDeviceMappedWestKeycode() {
        val c = testController(bridgeWith(sample))
        c.setInputMapping(
            IgmInputMapping(
                buttonKeycodes = mapOf(CanonicalButton.BTN_WEST to listOf(100)),
                menuConfirm = CanonicalButton.BTN_EAST,
                menuBack = CanonicalButton.BTN_SOUTH,
            )
        )
        c.openMenu(); c.openAchievements()
        val before = (c.currentScreen as IGMScreen.Achievements).filter
        c.handleKeyDown(100)
        val after = (c.currentScreen as IGMScreen.Achievements).filter
        assertEquals((before + 1) % 3, after)
    }

    @Test fun filterDoesNotCycleWhenAllUnlocked() {
        val allUnlocked = listOf(
            AchievementInfo(1, "Alpha", "first", 5, unlocked = true),
            AchievementInfo(2, "Beta", "second", 10, unlocked = true),
        )
        val c = testController(bridgeWith(allUnlocked))
        c.openMenu(); c.openAchievements()
        val before = (c.currentScreen as IGMScreen.Achievements).filter
        c.handleKeyDown(99)
        val after = (c.currentScreen as IGMScreen.Achievements).filter
        assertEquals(before, after)
    }
}
