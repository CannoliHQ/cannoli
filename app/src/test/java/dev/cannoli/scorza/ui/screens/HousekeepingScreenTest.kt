package dev.cannoli.scorza.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import dev.cannoli.scorza.R
import dev.cannoli.ui.theme.CannoliTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w640dp-h480dp-xhdpi")
class HousekeepingScreenTest {

    @get:Rule val compose = createComposeRule()

    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

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
