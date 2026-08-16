package dev.cannoli.scorza.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OnboardingStepTest {

    @Test fun stepsAreNumberedFromOne() {
        assertEquals(1, OnboardingStep.WELCOME.number)
        assertEquals(2, OnboardingStep.PERMISSIONS.number)
        assertEquals(3, OnboardingStep.STORAGE.number)
        assertEquals(3, OnboardingStep.COUNT)
    }

    @Test fun progressionRunsWelcomeToPermissionsToStorage() {
        assertEquals(OnboardingStep.PERMISSIONS, OnboardingStep.WELCOME.next)
        assertEquals(OnboardingStep.STORAGE, OnboardingStep.PERMISSIONS.next)
        assertNull(OnboardingStep.STORAGE.next)
    }

    @Test fun backwardsProgressionStopsAtTheFirstStep() {
        assertNull(OnboardingStep.WELCOME.previous)
        assertEquals(OnboardingStep.WELCOME, OnboardingStep.PERMISSIONS.previous)
        assertEquals(OnboardingStep.PERMISSIONS, OnboardingStep.STORAGE.previous)
    }
}
