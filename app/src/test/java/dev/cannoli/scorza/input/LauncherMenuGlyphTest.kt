package dev.cannoli.scorza.input

import dev.cannoli.igm.ShortcutAction
import dev.cannoli.ui.theme.MenuGlyph
import org.junit.Assert.assertEquals
import org.junit.Test

class LauncherMenuGlyphTest {

    private fun pad(menu: Boolean) = DeviceMapping(
        id = "pad",
        displayName = "Pad",
        match = DeviceMatchRule(),
        bindings = buildMap {
            put(CanonicalButton.BTN_SELECT, listOf(InputBinding.Button(109)))
            put(CanonicalButton.BTN_START, listOf(InputBinding.Button(108)))
            if (menu) put(CanonicalButton.BTN_MENU, listOf(InputBinding.Button(82)))
        },
        source = MappingSource.USER_WIZARD,
    )

    private val chord = mapOf(ShortcutAction.OPEN_MENU to setOf(109, 108))

    @Test fun `the wizard's chord on a pad without a menu button draws select and start`() {
        assertEquals(MenuGlyph.SelectStart, launcherMenuGlyph(pad(menu = false), chord))
    }

    @Test fun `a pad with a menu button switches it back`() {
        assertEquals(MenuGlyph.Menu, launcherMenuGlyph(pad(menu = true), chord))
    }

    @Test fun `no active pad draws the menu glyph`() {
        assertEquals(MenuGlyph.Menu, launcherMenuGlyph(null, chord))
    }

    @Test fun `a shortcut change is followed`() {
        val p = pad(menu = false)
        assertEquals(MenuGlyph.Menu, launcherMenuGlyph(p, emptyMap()))
        assertEquals(MenuGlyph.HoldStart, launcherMenuGlyph(p, mapOf(ShortcutAction.OPEN_MENU_HOLD to setOf(108))))
    }
}
