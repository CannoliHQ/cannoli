package dev.cannoli.igm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IGMControllerAchievementsTest {
    private fun bridgeWith(achievements: List<AchievementInfo>) = object : FakeEmulatorBridge() {
        override val supportsAchievements = true
        override fun getAchievements() = achievements
    }

    private val sample = listOf(
        AchievementInfo(1, "Alpha", "first", 5, unlocked = true),
        AchievementInfo(2, "Beta", "second", 10, unlocked = false),
    )

    @Test fun openAchievementsPushesPopulatedScreen() {
        val c = IGMController(bridgeWith(sample), "Game")
        c.openMenu(); c.openAchievements()
        val screen = c.currentScreen
        assertTrue(screen is IGMScreen.Achievements)
        assertEquals(2, (screen as IGMScreen.Achievements).achievements.size)
    }

    @Test fun filterCyclesWithWest() {
        val c = IGMController(bridgeWith(sample), "Game")
        c.openMenu(); c.openAchievements()
        c.handleKeyDown(99)
        assertEquals(1, (c.currentScreen as IGMScreen.Achievements).filter)
    }

    @Test fun southOpensDetailEastReturns() {
        val c = IGMController(bridgeWith(sample), "Game")
        c.openMenu(); c.openAchievements()
        c.handleKeyDown(96)
        assertTrue(c.currentScreen is IGMScreen.AchievementDetail)
        c.handleKeyDown(4)
        assertTrue(c.currentScreen is IGMScreen.Achievements)
    }
}
