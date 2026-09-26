package dev.cannoli.igm

import dev.cannoli.ui.theme.MenuGlyph

/**
 * What a legend should draw for the menu button on this pad. Shared by the launcher and the in-game
 * menu, which each pass what they know about the active pad and the shortcuts in force.
 *
 * A menu button wins: it is what the pad has. Without one, only the two shapes the setup wizard
 * writes get a drawing of their own, select + start on the pad's own select and start, or holding
 * the pad's start. Any other chord was bound by hand and keeps the menu glyph, since there is no
 * telling what it looks like on the pad.
 */
fun menuGlyphFor(
    menuButtonBound: Boolean,
    selectKeys: Collection<Int>,
    startKeys: Collection<Int>,
    shortcuts: Map<ShortcutAction, Set<Int>>,
): MenuGlyph {
    if (menuButtonBound) return MenuGlyph.Menu
    val chord = shortcuts[ShortcutAction.OPEN_MENU].orEmpty()
    if (chord.isNotEmpty()) {
        val selectStart = chord.size == 2 && chord.any { it in selectKeys } && chord.any { it in startKeys }
        return if (selectStart) MenuGlyph.SelectStart else MenuGlyph.Menu
    }
    val hold = shortcuts[ShortcutAction.OPEN_MENU_HOLD].orEmpty()
    if (hold.size == 1 && hold.single() in startKeys) return MenuGlyph.HoldStart
    return MenuGlyph.Menu
}

/** [menuGlyphFor] for the in-game menu, which knows the pad by its [IgmInputMapping]. */
fun menuGlyphFor(mapping: IgmInputMapping?, shortcuts: Map<ShortcutAction, Set<Int>>): MenuGlyph =
    if (mapping == null) {
        MenuGlyph.Menu
    } else {
        menuGlyphFor(
            menuButtonBound = mapping.buttonKeycodes[CanonicalButton.BTN_MENU].orEmpty().isNotEmpty(),
            selectKeys = mapping.buttonKeycodes[CanonicalButton.BTN_SELECT].orEmpty(),
            startKeys = mapping.buttonKeycodes[CanonicalButton.BTN_START].orEmpty(),
            shortcuts = shortcuts,
        )
    }
