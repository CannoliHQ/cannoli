package dev.cannoli.scorza.libretro

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HoldFfTest {

    @Test
    fun holdsWhileWholeChordStaysPressed() {
        assertFalse(shouldReleaseHoldFf(setOf(102, 103), setOf(102, 103, 96)))
    }

    @Test
    fun releasesOnceAChordKeyLifts() {
        assertTrue(shouldReleaseHoldFf(setOf(102, 103), setOf(102)))
    }

    @Test
    fun releasesWhenBindingClearedWhileHeld() {
        assertTrue(shouldReleaseHoldFf(emptySet(), setOf(102, 103)))
    }

    @Test
    fun releasesWhenBindingRemovedWhileHeld() {
        assertTrue(shouldReleaseHoldFf(null, setOf(102, 103)))
    }
}
