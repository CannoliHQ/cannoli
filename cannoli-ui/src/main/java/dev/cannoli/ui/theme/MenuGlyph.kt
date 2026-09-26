package dev.cannoli.ui.theme

import androidx.compose.runtime.compositionLocalOf
import dev.cannoli.ui.HOLD_START_GLYPH
import dev.cannoli.ui.MENU_GLYPH
import dev.cannoli.ui.SELECT_START_GLYPH

/**
 * How this pad opens the menu, which is what a legend naming the menu button has to show. A pad
 * with no menu button opens it with a shortcut instead, and drawing a button it does not have
 * sends the user looking for it.
 */
enum class MenuGlyph { Menu, SelectStart, HoldStart }

/** Provided at the launcher's root and the in-game menu's, from the active pad and its shortcuts. */
val LocalMenuGlyph = compositionLocalOf { MenuGlyph.Menu }

/** The glyphs a legend shows for the menu button, one pill each, spelled the way legends spell them. */
fun MenuGlyph.glyphs(): List<String> = when (this) {
    MenuGlyph.Menu -> listOf(MENU_GLYPH)
    MenuGlyph.SelectStart -> listOf(SELECT_START_GLYPH)
    MenuGlyph.HoldStart -> listOf(HOLD_START_GLYPH)
}
