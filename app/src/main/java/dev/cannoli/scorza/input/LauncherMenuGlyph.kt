package dev.cannoli.scorza.input

import dev.cannoli.igm.ShortcutAction
import dev.cannoli.igm.menuGlyphFor
import dev.cannoli.ui.theme.MenuGlyph

/** [menuGlyphFor] for the launcher, which knows the active pad by its [DeviceMapping]. */
fun launcherMenuGlyph(mapping: DeviceMapping?, shortcuts: Map<ShortcutAction, Set<Int>>): MenuGlyph {
    if (mapping == null) return MenuGlyph.Menu
    fun keys(button: CanonicalButton) =
        mapping.bindings[button].orEmpty().filterIsInstance<InputBinding.Button>().map { it.keyCode }
    return menuGlyphFor(
        menuButtonBound = mapping.bindings[CanonicalButton.BTN_MENU].orEmpty().isNotEmpty(),
        selectKeys = keys(CanonicalButton.BTN_SELECT),
        startKeys = keys(CanonicalButton.BTN_START),
        shortcuts = shortcuts,
    )
}
