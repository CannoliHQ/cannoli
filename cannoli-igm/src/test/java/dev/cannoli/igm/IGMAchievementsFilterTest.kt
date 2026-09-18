package dev.cannoli.igm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val KEY_WEST = 99
private const val KEY_SOUTH = 96

class IGMAchievementsFilterTest {

    private val mapping = IgmInputMapping(
        buttonKeycodes = mapOf(
            CanonicalButton.BTN_WEST to listOf(KEY_WEST),
            CanonicalButton.BTN_SOUTH to listOf(KEY_SOUTH),
        ),
        menuConfirm = CanonicalButton.BTN_SOUTH,
        menuBack = CanonicalButton.BTN_EAST,
    )

    private fun ach(id: Int, unlocked: Boolean, pending: Boolean = false) =
        AchievementInfo(id = id, title = "A$id", description = "", points = 5, unlocked = unlocked, pendingSync = pending)

    private fun controllerWith(list: List<AchievementInfo>): IGMController =
        testController(object : FakeRetroArchBridge() {
            override fun getAchievements(): List<AchievementInfo> = list
        }).apply {
            setInputMapping(mapping)
            openMenu()
            openAchievements()
        }

    private fun screen(c: IGMController) = c.currentScreen as IGMScreen.Achievements

    @Test fun `the filter cycles through unsynced when something is unsynced`() {
        val c = controllerWith(listOf(ach(1, true), ach(2, false), ach(3, true, pending = true)))
        assertEquals(0, screen(c).filter)
        c.handleKeyDown(KEY_WEST)
        assertEquals(1, screen(c).filter)
        c.handleKeyDown(KEY_WEST)
        assertEquals(2, screen(c).filter)
        c.handleKeyDown(KEY_WEST)
        assertEquals(3, screen(c).filter)
        c.handleKeyDown(KEY_WEST)
        assertEquals(0, screen(c).filter)
    }

    @Test fun `with nothing unsynced the cycle skips that bucket`() {
        val c = controllerWith(listOf(ach(1, true), ach(2, false)))
        c.handleKeyDown(KEY_WEST)
        c.handleKeyDown(KEY_WEST)
        c.handleKeyDown(KEY_WEST)
        assertEquals(0, screen(c).filter)
    }

    @Test fun `the unsynced bucket lists only what is waiting to be submitted`() {
        val list = listOf(ach(1, true), ach(2, false), ach(3, true, pending = true))
        val c = controllerWith(list)
        repeat(3) { c.handleKeyDown(KEY_WEST) }
        val shown = list.filter { it.pendingSync }
        assertEquals(1, shown.size)
        assertTrue(shown.single().id == 3)
    }
}
