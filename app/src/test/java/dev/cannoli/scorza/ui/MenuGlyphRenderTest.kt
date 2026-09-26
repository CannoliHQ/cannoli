package dev.cannoli.scorza.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import dev.cannoli.igm.GuideOsdText
import dev.cannoli.ui.HOLD_START_GLYPH
import dev.cannoli.ui.MENU_GLYPH
import dev.cannoli.ui.SELECT_START_GLYPH
import dev.cannoli.ui.components.BottomBar
import dev.cannoli.ui.components.HelpEntry
import dev.cannoli.ui.components.HelpGlyph
import dev.cannoli.ui.components.HelpGroup
import dev.cannoli.ui.components.HelpOverlay
import dev.cannoli.ui.theme.LocalMenuGlyph
import dev.cannoli.ui.theme.MenuGlyph
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Every legend that names the menu button draws it through [MENU_GLYPH], which the glyph pill and
 * the guide's hint resolve against [LocalMenuGlyph]. These pin both halves: the sites pass the
 * menu glyph, and what they pass is drawn as whatever the pad opens the menu with.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MenuGlyphRenderTest {
    @get:Rule val compose = createComposeRule()

    private fun legend(glyph: MenuGlyph) = compose.setContent {
        CompositionLocalProvider(LocalMenuGlyph provides glyph) {
            BottomBar(leftItems = listOf(MENU_GLYPH to "Menu"), rightItems = emptyList())
        }
    }

    private fun help(glyph: MenuGlyph) = compose.setContent {
        CompositionLocalProvider(LocalMenuGlyph provides glyph) {
            HelpOverlay(
                titleRes = dev.cannoli.ui.R.string.label_help,
                groups = listOf(
                    HelpGroup(dev.cannoli.ui.R.string.label_help, listOf(HelpEntry(listOf(HelpGlyph.MENU), dev.cannoli.ui.R.string.label_help))),
                ),
            )
        }
    }

    private fun hint(glyph: MenuGlyph) = compose.setContent {
        CompositionLocalProvider(LocalMenuGlyph provides glyph) {
            Row { GuideOsdText("$MENU_GLYPH Help", helpHint = "$MENU_GLYPH Help") }
        }
    }

    // A pill each: what a pad with a menu button reads, and what the two shortcuts read.
    private fun assertPills(menu: Int, selectStart: Int, holdStart: Int) {
        compose.onAllNodesWithText(MENU_GLYPH).assertCountEquals(menu)
        compose.onAllNodesWithText(SELECT_START_GLYPH).assertCountEquals(selectStart)
        compose.onAllNodesWithText(HOLD_START_GLYPH).assertCountEquals(holdStart)
    }

    @Test fun `a legend keeps the menu glyph for a pad with a menu button`() {
        legend(MenuGlyph.Menu)
        assertPills(menu = 1, selectStart = 0, holdStart = 0)
    }

    @Test fun `a legend spells select and start`() {
        legend(MenuGlyph.SelectStart)
        assertPills(menu = 0, selectStart = 1, holdStart = 0)
        compose.onNodeWithText("Menu").assertExists()
    }

    @Test fun `a legend spells hold start`() {
        legend(MenuGlyph.HoldStart)
        assertPills(menu = 0, selectStart = 0, holdStart = 1)
    }

    @Test fun `a help page row keeps the menu glyph for a pad with a menu button`() {
        help(MenuGlyph.Menu)
        assertPills(menu = 1, selectStart = 0, holdStart = 0)
    }

    @Test fun `a help page row spells select and start`() {
        help(MenuGlyph.SelectStart)
        assertPills(menu = 0, selectStart = 1, holdStart = 0)
    }

    @Test fun `a help page row spells hold start`() {
        help(MenuGlyph.HoldStart)
        assertPills(menu = 0, selectStart = 0, holdStart = 1)
    }

    @Test fun `the guide hint keeps its text glyph for a menu button`() {
        hint(MenuGlyph.Menu)
        compose.onNodeWithText("$MENU_GLYPH Help").assertExists()
    }

    @Test fun `the guide hint spells select and start`() {
        hint(MenuGlyph.SelectStart)
        compose.onNodeWithText("$SELECT_START_GLYPH Help").assertExists()
        compose.onAllNodesWithText(MENU_GLYPH, substring = true).assertCountEquals(0)
    }

    @Test fun `the guide hint spells hold start`() {
        hint(MenuGlyph.HoldStart)
        compose.onNodeWithText("$HOLD_START_GLYPH Help").assertExists()
        compose.onAllNodesWithText(MENU_GLYPH, substring = true).assertCountEquals(0)
    }

    @Test fun `every site names the menu through the glyph that resolves`() {
        fun src(path: String) = File("..", path).readText()
        assertTrue(src("app/src/main/java/dev/cannoli/scorza/ui/screens/SystemListScreen.kt").contains("add(MENU_GLYPH to stringResource(R.string.label_menu))"))
        assertTrue(src("cannoli-ui/src/main/java/dev/cannoli/ui/components/KeyboardOverlay.kt").contains("MENU_GLYPH to stringResource(R.string.label_help)"))
        assertTrue(src("cannoli-igm/src/main/java/dev/cannoli/igm/CannoliIGM.kt").contains("MENU_GLYPH to stringResource(dev.cannoli.ui.R.string.label_help)"))
        assertTrue(src("cannoli-ui/src/main/java/dev/cannoli/ui/components/HelpOverlay.kt").contains("HelpGlyph.MENU -> MENU_GLYPH"))
        assertTrue(src("cannoli-igm/src/main/java/dev/cannoli/igm/GuideScreen.kt").contains("OsdHost(osd) { message -> GuideOsdText(message, helpHint) }"))
        assertTrue(src("cannoli-igm/src/main/java/dev/cannoli/igm/CannoliIGM.kt").contains("LocalMenuGlyph provides menuGlyph"))
        assertTrue(src("app/src/main/java/dev/cannoli/scorza/MainActivity.kt").contains("dev.cannoli.ui.theme.LocalMenuGlyph provides"))
    }
}
