package dev.cannoli.igm

import dev.cannoli.ui.theme.MenuGlyph
import org.junit.Assert.assertEquals
import org.junit.Test

private const val SELECT = 109
private const val START = 108
private const val MENU = 82

class MenuGlyphResolverTest {

    private fun resolve(menuButton: Boolean, shortcuts: Map<ShortcutAction, Set<Int>>) =
        menuGlyphFor(menuButton, listOf(SELECT), listOf(START), shortcuts)

    @Test fun `a menu button draws the menu glyph`() {
        assertEquals(MenuGlyph.Menu, resolve(true, emptyMap()))
    }

    @Test fun `a menu button beats a bound chord`() {
        assertEquals(MenuGlyph.Menu, resolve(true, mapOf(ShortcutAction.OPEN_MENU to setOf(SELECT, START))))
    }

    @Test fun `select and start draws its icon`() {
        assertEquals(MenuGlyph.SelectStart, resolve(false, mapOf(ShortcutAction.OPEN_MENU to setOf(SELECT, START))))
    }

    @Test fun `hold start draws its icon`() {
        assertEquals(MenuGlyph.HoldStart, resolve(false, mapOf(ShortcutAction.OPEN_MENU_HOLD to setOf(START))))
    }

    @Test fun `a chord bound by hand keeps the menu glyph`() {
        assertEquals(MenuGlyph.Menu, resolve(false, mapOf(ShortcutAction.OPEN_MENU to setOf(102, 103))))
        assertEquals(MenuGlyph.Menu, resolve(false, mapOf(ShortcutAction.OPEN_MENU to setOf(SELECT, START, 102))))
        assertEquals(MenuGlyph.Menu, resolve(false, mapOf(ShortcutAction.OPEN_MENU_HOLD to setOf(102))))
    }

    @Test fun `nothing bound keeps the menu glyph`() {
        assertEquals(MenuGlyph.Menu, resolve(false, emptyMap()))
    }

    @Test fun `the in-game menu follows its pad and its shortcuts`() {
        val c = testController(FakeRetroArchBridge())
        fun mapping(menu: Boolean) = IgmInputMapping(
            buttonKeycodes = buildMap {
                put(CanonicalButton.BTN_SELECT, listOf(SELECT))
                put(CanonicalButton.BTN_START, listOf(START))
                if (menu) put(CanonicalButton.BTN_MENU, listOf(MENU))
            },
            menuConfirm = CanonicalButton.BTN_SOUTH,
            menuBack = CanonicalButton.BTN_EAST,
        )
        assertEquals(MenuGlyph.Menu, c.menuGlyph.value)
        c.setInputMapping(mapping(menu = false))
        c.setMenuShortcuts(mapOf(ShortcutAction.OPEN_MENU to setOf(SELECT, START)))
        assertEquals(MenuGlyph.SelectStart, c.menuGlyph.value)
        c.setMenuShortcuts(mapOf(ShortcutAction.OPEN_MENU_HOLD to setOf(START)))
        assertEquals(MenuGlyph.HoldStart, c.menuGlyph.value)
        c.setInputMapping(mapping(menu = true))
        assertEquals(MenuGlyph.Menu, c.menuGlyph.value)
    }
}
