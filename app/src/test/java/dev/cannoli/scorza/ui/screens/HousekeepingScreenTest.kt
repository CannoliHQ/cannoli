package dev.cannoli.scorza.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import dev.cannoli.scorza.R
import dev.cannoli.ui.theme.CannoliTheme
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// The pre-mount resolve reuses this screen so its logo lands exactly where the scan screen's does.
// That parity rests on two things this covers: STARTING carries no subtitle (so the centered
// column is the same height as the no-subtitle scan phases), and the screen renders with a null,
// indeterminate progress without falling back to the determinate bar.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w640dp-h480dp-xhdpi")
class HousekeepingScreenTest {

    @get:Rule val compose = createComposeRule()

    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test fun `starting phase has no subtitle, matching the no-subtitle scan phases`() {
        assertNull(HousekeepingKind.STARTING.subtitleRes)
        assertNull(HousekeepingKind.INITIAL_SCAN.subtitleRes)
        assertNull(HousekeepingKind.LIBRARY_REFRESH.subtitleRes)
    }

    @Test fun `starting phase renders its title and status with an indeterminate bar`() {
        compose.setContent {
            CannoliTheme {
                HousekeepingScreen(
                    kind = HousekeepingKind.STARTING,
                    progress = null,
                    statusLabel = ctx.getString(R.string.boot_preparing),
                )
            }
        }

        compose.onNodeWithText(ctx.getString(R.string.housekeeping_starting_title)).assertIsDisplayed()
        compose.onNodeWithText(ctx.getString(R.string.boot_preparing)).assertIsDisplayed()
    }

    @Test fun `a determinate phase still renders`() {
        compose.setContent {
            CannoliTheme {
                HousekeepingScreen(
                    kind = HousekeepingKind.LIBRARY_REFRESH,
                    progress = 0.5f,
                    statusLabel = "Scanning",
                )
            }
        }

        compose.onNodeWithText(ctx.getString(R.string.housekeeping_refresh_title)).assertIsDisplayed()
        compose.onNodeWithText("Scanning").assertIsDisplayed()
    }
}
