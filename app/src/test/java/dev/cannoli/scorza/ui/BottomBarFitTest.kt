package dev.cannoli.scorza.ui

import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.cannoli.ui.MENU_GLYPH
import dev.cannoli.ui.components.BottomBar
import dev.cannoli.ui.theme.CannoliTheme
import dev.cannoli.ui.theme.LocalMenuGlyph
import dev.cannoli.ui.theme.LocalScaleFactor
import dev.cannoli.ui.theme.MenuGlyph
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The five-game system list footer in German on a pad that opens the menu with Select + Start,
 * which overflowed a 640x480 hdpi screen's 387dp bar on a Nova and cut RESUME to "FORTSE...".
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w640dp-h480dp-hdpi")
class BottomBarFitTest {
    @get:Rule val compose = createComposeRule()

    private val width = mutableStateOf(0.dp)

    private fun bar(width: Dp) {
        this.width.value = width
        compose.setContent { Footer() }
    }

    @Composable
    private fun Footer() {
        CannoliTheme {
            CompositionLocalProvider(
                LocalScaleFactor provides 24f / 22f,
                LocalMenuGlyph provides MenuGlyph.SelectStart,
            ) {
                BottomBar(
                    modifier = Modifier.requiredWidth(width.value),
                    leftItems = listOf(MENU_GLYPH to "MENU"),
                    rightItems = listOf("X" to "ABSPIELEN", "A" to "FORTSETZEN"),
                )
            }
        }
    }

    private fun SemanticsNodeInteraction.layout(): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
        return results.single()
    }

    private fun ellipsised(text: String) = compose.onNodeWithText(text).layout().isLineEllipsized(0)

    private fun rightEdge(text: String) = compose.onNodeWithText(text).getUnclippedBoundsInRoot().right

    @Test fun `the primary action keeps its text when the bar is too narrow`() {
        bar(387.dp)
        assertFalse(ellipsised("FORTSETZEN"))
        assertFalse(ellipsised("ABSPIELEN"))
        assertTrue(rightEdge("FORTSETZEN") <= 387.dp)
    }

    @Test fun `the left side keeps its text when scaling is enough`() {
        bar(387.dp)
        assertFalse(ellipsised("MENU"))
    }

    @Test fun `a footer that fits renders at full size`() {
        bar(2000.dp)
        val wide = compose.onNodeWithText("FORTSETZEN").getUnclippedBoundsInRoot().let { (it.right - it.left).value }
        width.value = 640.dp
        compose.waitForIdle()
        val fits = compose.onNodeWithText("FORTSETZEN").getUnclippedBoundsInRoot().let { (it.right - it.left).value }
        assertEquals(wide, fits, 0.01f)
    }

    @Test fun `below the floor the left side gives way, not the right`() {
        bar(320.dp)
        assertFalse(ellipsised("FORTSETZEN"))
        assertFalse(ellipsised("ABSPIELEN"))
        assertTrue(ellipsised("MENU"))
    }
}
