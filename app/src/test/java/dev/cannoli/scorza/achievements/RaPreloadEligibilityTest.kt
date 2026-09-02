package dev.cannoli.scorza.achievements

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RaPreloadEligibilityTest {
    @Test fun eligible_whenSignedInAndPlatformIsPublished() {
        assertTrue(RaPreloadEligibility.isEligible("SNES", raLoggedIn = true))
    }
    @Test fun ineligible_whenNotLoggedIn() {
        assertFalse(RaPreloadEligibility.isEligible("SNES", raLoggedIn = false))
    }
    @Test fun ineligible_whenPlatformNull() {
        assertFalse(RaPreloadEligibility.isEligible(null, raLoggedIn = true))
    }
    @Test fun ineligible_whenPlatformNotRaMapped() {
        assertFalse(RaPreloadEligibility.isEligible("3DS", raLoggedIn = true))
    }
    @Test fun platformTag_caseInsensitive() {
        assertTrue(RaPreloadEligibility.isEligible("snes", raLoggedIn = true))
    }
}
