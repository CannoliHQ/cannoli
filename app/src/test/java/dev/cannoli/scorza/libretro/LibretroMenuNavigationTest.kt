package dev.cannoli.scorza.libretro

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibretroMenuNavigationTest {

    @Test
    fun `gameplay does not poll shared menu navigation state`() {
        assertFalse(shouldPollInGameMenuNavigation(hasMenuScreens = false, activityResumed = true))
    }

    @Test
    fun `visible in-game menu polls navigation while activity is resumed`() {
        assertTrue(shouldPollInGameMenuNavigation(hasMenuScreens = true, activityResumed = true))
    }

    @Test
    fun `paused activity does not poll even when menu remains open`() {
        assertFalse(shouldPollInGameMenuNavigation(hasMenuScreens = true, activityResumed = false))
    }
}
