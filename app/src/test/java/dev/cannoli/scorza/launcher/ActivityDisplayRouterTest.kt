package dev.cannoli.scorza.launcher

import android.view.Display
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ActivityDisplayRouterTest {

    @Test fun `enabling covers the game display before moving Home away`() {
        assertEquals(
            LauncherDisplayTransition.COVER_GAME_DISPLAY_THEN_MOVE,
            launcherDisplayTransition(
                currentDisplayId = Display.DEFAULT_DISPLAY,
                targetDisplayId = 4,
                dualScreenActive = true,
            ),
        )
    }

    @Test fun `disabling moves Home before removing the game display cover`() {
        assertEquals(
            LauncherDisplayTransition.MOVE_THEN_SYNC_ON_DESTINATION,
            launcherDisplayTransition(
                currentDisplayId = 4,
                targetDisplayId = Display.DEFAULT_DISPLAY,
                dualScreenActive = false,
            ),
        )
    }

    @Test fun `same display settings changes synchronize without relocation`() {
        assertEquals(
            LauncherDisplayTransition.SYNC_IN_PLACE,
            launcherDisplayTransition(
                currentDisplayId = 4,
                targetDisplayId = 4,
                dualScreenActive = true,
            ),
        )
    }

    @Test fun `display availability changes wait for a visible default display and game exit`() {
        assertEquals(
            true,
            shouldApplyDisplayAvailabilityChange(
                defaultDisplayActive = true,
                dualScreenActive = false,
                lastDualScreenActive = true,
                gameActive = false,
            ),
        )
        assertEquals(
            false,
            shouldApplyDisplayAvailabilityChange(true, false, true, gameActive = true),
        )
        assertEquals(
            false,
            shouldApplyDisplayAvailabilityChange(false, false, true, gameActive = false),
        )
        assertEquals(
            false,
            shouldApplyDisplayAvailabilityChange(true, true, true, gameActive = false),
        )
    }

    @Test fun `dual-screen routing is gated by experimental features`() {
        assertEquals(false, isDualScreenRoutingEnabled(false, true))
        assertEquals(false, isDualScreenRoutingEnabled(true, false))
        assertEquals(true, isDualScreenRoutingEnabled(true, true))
    }

    @Test fun `Thor panel mode overrides stale Android display state`() {
        assertEquals(true, isDisplayActive(Display.DEFAULT_DISPLAY, Display.STATE_ON, 0))
        assertEquals(true, isDisplayActive(4, Display.STATE_ON, 0))
        assertEquals(false, isDisplayActive(4, Display.STATE_ON, 1))
        assertEquals(true, isDisplayActive(4, Display.STATE_ON, null))
        assertEquals(false, isDisplayActive(4, Display.STATE_OFF, 1))
    }

    @Test fun `launcher uses smaller display and game uses larger display`() {
        val displays = listOf(
            DisplayCandidate(Display.DEFAULT_DISPLAY, 0, true, 1920, 1080, 15.0),
            DisplayCandidate(7, Display.FLAG_PRESENTATION, true, 1280, 720, 5.0),
        )

        assertEquals(DisplaySizeRoute(7, Display.DEFAULT_DISPLAY), selectDisplaySizeRoute(displays))
    }

    @Test fun `Android default display can be the smaller display`() {
        val displays = listOf(
            DisplayCandidate(Display.DEFAULT_DISPLAY, 0, true, 1280, 720, 5.0),
            DisplayCandidate(7, Display.FLAG_PRESENTATION, true, 1920, 1080, 15.0),
        )

        assertEquals(DisplaySizeRoute(Display.DEFAULT_DISPLAY, 7), selectDisplaySizeRoute(displays))
    }

    @Test fun `physical area wins when smaller screen has more pixels`() {
        val displays = listOf(
            DisplayCandidate(Display.DEFAULT_DISPLAY, 0, true, 3840, 2160, 5.0),
            DisplayCandidate(7, Display.FLAG_PRESENTATION, true, 1920, 1080, 15.0),
        )

        assertEquals(DisplaySizeRoute(Display.DEFAULT_DISPLAY, 7), selectDisplaySizeRoute(displays))
    }

    @Test fun `private and invalid displays are ignored`() {
        val displays = listOf(
            DisplayCandidate(Display.DEFAULT_DISPLAY, 0, true, 1920, 1080),
            DisplayCandidate(2, Display.FLAG_PRIVATE, true, 1280, 720),
            DisplayCandidate(3, 0, false, 1280, 720),
        )

        assertNull(selectDisplaySizeRoute(displays))
    }

    @Test fun `powered off displays fall back to a single screen route`() {
        val displays = listOf(
            DisplayCandidate(Display.DEFAULT_DISPLAY, 0, true, 1920, 1080, 15.0),
            DisplayCandidate(
                id = 4,
                flags = Display.FLAG_PRESENTATION,
                isValid = true,
                widthPixels = 1280,
                heightPixels = 720,
                physicalAreaSquareInches = 5.0,
                isActive = false,
            ),
        )

        assertNull(selectDisplaySizeRoute(displays))
    }
}
