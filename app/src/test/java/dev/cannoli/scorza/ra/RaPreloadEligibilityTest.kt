package dev.cannoli.scorza.ra

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RaPreloadEligibilityTest {
    @Test fun eligible_whenAllConditionsMet() {
        assertTrue(RaPreloadEligibility.isEligible("SNES", embeddedCorePresent = true, raLoggedIn = true))
    }
    @Test fun ineligible_whenNotEmbeddedCore() {
        assertFalse(RaPreloadEligibility.isEligible("SNES", embeddedCorePresent = false, raLoggedIn = true))
    }
    @Test fun ineligible_whenNotLoggedIn() {
        assertFalse(RaPreloadEligibility.isEligible("SNES", embeddedCorePresent = true, raLoggedIn = false))
    }
    @Test fun eligible_evenWithoutKnownGameId() {
        assertTrue(RaPreloadEligibility.isEligible("SNES", embeddedCorePresent = true, raLoggedIn = true))
    }
    @Test fun ineligible_whenPlatformNotRaMapped() {
        assertFalse(RaPreloadEligibility.isEligible("3DS", embeddedCorePresent = true, raLoggedIn = true))
    }
    @Test fun platformTag_caseInsensitive() {
        assertTrue(RaPreloadEligibility.isEligible("snes", embeddedCorePresent = true, raLoggedIn = true))
    }
}
