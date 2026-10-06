package dev.cannoli.igm

import dev.cannoli.ui.theme.MenuGlyph

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
