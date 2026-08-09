package dev.cannoli.scorza.ui.components

import dev.cannoli.scorza.navigation.LauncherScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RaAccountRowTest {

    @Test fun `the rows are in the designed order`() {
        assertEquals(
            listOf(
                RaAccountRow.ACCOUNT,
                RaAccountRow.HARDCORE,
                RaAccountRow.OFFLINE_SETS,
            ),
            RaAccountRow.entries.toList(),
        )
    }

    @Test fun `only hardcore cycles on left and right`() {
        assertTrue(RaAccountRow.HARDCORE.isCycle)
        assertTrue(RaAccountRow.entries.filter { it.isCycle } == listOf(RaAccountRow.HARDCORE))
    }

    @Test fun `the account screen navigates as a list`() {
        val screen = LauncherScreen.RetroAchievements(username = "bob")
        assertEquals(0, screen.selectedIndex)
        assertEquals(
            2,
            (screen.withScroll(selectedIndex = 2, scrollTarget = 0) as LauncherScreen.RetroAchievements).selectedIndex,
        )
    }
}
