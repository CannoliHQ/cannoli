package dev.cannoli.scorza.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import dev.cannoli.ui.components.LaunchIssue
import dev.cannoli.ui.theme.CannoliTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The screen measures the space it has so a long subject shrinks rather than pushing the title off
 * a short screen. Measuring meant reading `lineHeight`, and the theme's `titleLarge` leaves that
 * unspecified: `TextUnit.toPx` throws on anything that is not Sp, so the first version crashed the
 * app on every launch failure. Rendering under the real theme is what catches that.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LaunchIssueRenderTest {
    @get:Rule val compose = createComposeRule()

    private fun render(title: String, subject: String, confirm: String? = null) {
        compose.setContent {
            CannoliTheme { LaunchIssue(title = title, subject = subject, confirmLabel = confirm) }
        }
    }

    @Test fun `renders under the real theme, whose titleLarge has no lineHeight`() {
        render("Required BIOS Missing", "Sega Saturn\nsega_101.bin\nmpr-17933.bin", "VIEW BIOS")
        compose.onNodeWithText("Required BIOS Missing").assertIsDisplayed()
        compose.onNodeWithText("VIEW BIOS").assertIsDisplayed()
    }

    // Three lines now: the problem, the pairing, and what the core does read. The third is the
    // longest, so it is the one that would push the title off a short screen if it were not capped.
    @Test fun `the unsupported format screen renders all three lines`() {
        render("Core Does Not Support .zip", "Neo Geo \u00b7 Geolith Supports:\n.neo, .cue, .chd", "CHANGE EMULATOR")
        compose.onNodeWithText("Core Does Not Support .zip").assertIsDisplayed()
        compose.onNodeWithText("Neo Geo \u00b7 Geolith Supports:\n.neo, .cue, .chd").assertIsDisplayed()
        compose.onNodeWithText("CHANGE EMULATOR").assertIsDisplayed()
    }

    // Six short formats fit, so a list is only trimmed when it genuinely will not.
    @Test fun `a six format list is shown in full`() {
        render(
            "Core Does Not Support .zip",
            "Super Nintendo \u00b7 Snes9x Supports:\n.smc, .sfc, .swc, .fig, .bs, .st",
            "CHANGE EMULATOR",
        )
        compose.onNodeWithText("Super Nintendo \u00b7 Snes9x Supports:\n.smc, .sfc, .swc, .fig, .bs, .st")
            .assertIsDisplayed()
    }

    @Test fun `a trimmed format list still renders`() {
        render(
            "Core Does Not Support .rom",
            "Amiga \u00b7 PUAE Supports:\n.adf, .adz, .dms, .fdi, .ipf and 17 more",
            "CHANGE EMULATOR",
        )
        compose.onNodeWithText("Core Does Not Support .rom").assertIsDisplayed()
    }

    @Test fun `a long subject still renders every line`() {
        render(
            "Required BIOS Missing",
            "Amiga\nkick34005.A500\nkick40068.A1200\nkick40060.CD32\nkick40060.CD32.ext",
            "VIEW BIOS",
        )
        compose.onNodeWithText("Required BIOS Missing").assertIsDisplayed()
    }

    @Test fun `with no remedy only Close is offered`() {
        render("Game File Not Found", "Sonic the Hedgehog 2")
        compose.onNodeWithText("Game File Not Found").assertIsDisplayed()
        compose.onNodeWithText("CLOSE").assertIsDisplayed()
    }
}
