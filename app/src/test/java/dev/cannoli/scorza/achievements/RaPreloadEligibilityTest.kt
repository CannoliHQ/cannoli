package dev.cannoli.scorza.achievements

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RaPreloadEligibilityTest {
    @Test fun eligible_whenAllConditionsMet() {
        assertTrue(RaPreloadEligibility.isEligible("SNES", raLoggedIn = true, runnerSupportsAchievements = true))
    }
    @Test fun ineligible_whenRunnerCannotReportAchievements() {
        assertFalse(RaPreloadEligibility.isEligible("SNES", raLoggedIn = true, runnerSupportsAchievements = false))
    }
    @Test fun ineligible_whenNotLoggedIn() {
        assertFalse(RaPreloadEligibility.isEligible("SNES", raLoggedIn = false, runnerSupportsAchievements = true))
    }
    @Test fun ineligible_whenPlatformNull() {
        assertFalse(RaPreloadEligibility.isEligible(null, raLoggedIn = true, runnerSupportsAchievements = true))
    }
    @Test fun ineligible_whenPlatformNotRaMapped() {
        assertFalse(RaPreloadEligibility.isEligible("3DS", raLoggedIn = true, runnerSupportsAchievements = true))
    }
    @Test fun platformTag_caseInsensitive() {
        assertTrue(RaPreloadEligibility.isEligible("snes", raLoggedIn = true, runnerSupportsAchievements = true))
    }

    // Guards the dormant state itself: with the internal runner gone there is nothing that can
    // read a preloaded set, so the default must keep every row hidden.
    @Test fun ineligible_byDefaultWhileAchievementsAreDormant() {
        assertFalse(RaPreloadEligibility.isEligible("SNES", raLoggedIn = true))
    }
}
