package dev.cannoli.ui.theme

import androidx.compose.runtime.compositionLocalOf
import dev.cannoli.ui.HOLD_START_GLYPH
import dev.cannoli.ui.MENU_GLYPH
import dev.cannoli.ui.SELECT_START_GLYPH

enum class MenuGlyph { Menu, SelectStart, HoldStart }

val LocalMenuGlyph = compositionLocalOf { MenuGlyph.Menu }

fun MenuGlyph.glyphs(): List<String> = when (this) {
    MenuGlyph.Menu -> listOf(MENU_GLYPH)
    MenuGlyph.SelectStart -> listOf(SELECT_START_GLYPH)
    MenuGlyph.HoldStart -> listOf(HOLD_START_GLYPH)
}
