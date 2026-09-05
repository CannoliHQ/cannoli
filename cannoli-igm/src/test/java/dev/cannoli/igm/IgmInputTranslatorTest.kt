package dev.cannoli.igm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IgmInputTranslatorTest {

    // Retroid Pocket: East=96, South=97, North=99, West=100; confirm=East, back=South.
    private val retroid = IgmInputMapping(
        buttonKeycodes = mapOf(
            CanonicalButton.BTN_EAST to listOf(96),
            CanonicalButton.BTN_SOUTH to listOf(97),
            CanonicalButton.BTN_NORTH to listOf(99),
            CanonicalButton.BTN_WEST to listOf(100),
            CanonicalButton.BTN_L to listOf(102),
            CanonicalButton.BTN_R to listOf(103),
        ),
        menuConfirm = CanonicalButton.BTN_EAST,
        menuBack = CanonicalButton.BTN_SOUTH,
    )

    @Test fun `face buttons resolve by position, not by the code they report`() {
        val t = IgmInputTranslator(retroid)
        assertEquals(MenuAction.WEST, t.normalize(100))
        assertEquals(MenuAction.NORTH, t.normalize(99))
    }

    @Test fun confirmAndBackFollowMenuAssignment() {
        val t = IgmInputTranslator(retroid)
        assertEquals(MenuAction.CONFIRM, t.normalize(96))
        assertEquals(MenuAction.BACK, t.normalize(97))
    }

    @Test fun shoulderButtonsNormalize() {
        val t = IgmInputTranslator(retroid)
        assertEquals(MenuAction.L1, t.normalize(102))
        assertEquals(MenuAction.R1, t.normalize(103))
    }

    @Test fun dpadAndSystemBackPassThrough() {
        val t = IgmInputTranslator(retroid)
        assertEquals(MenuAction.UP, t.normalize(19))
        assertEquals(MenuAction.DOWN, t.normalize(20))
        assertEquals(MenuAction.LEFT, t.normalize(21))
        assertEquals(MenuAction.RIGHT, t.normalize(22))
        assertEquals(MenuAction.BACK, t.normalize(4))
    }

    // The Retroid Pocket Nova's menu button reports KEYCODE_BACK, and its cfg says so. Answering
    // from the pass-through first made that button back once you were inside the menu, so menu and
    // back were one key and nothing could be bound to menu.
    @Test fun `a menu button that reports back is still menu`() {
        val withMenu = retroid.copy(
            buttonKeycodes = retroid.buttonKeycodes + (CanonicalButton.BTN_MENU to listOf(4))
        )
        assertEquals(MenuAction.MENU, IgmInputTranslator(withMenu).normalize(4))
    }

    @Test fun `the menu key is whichever button the mapping names`() {
        val withMenu = retroid.copy(
            buttonKeycodes = retroid.buttonKeycodes + (CanonicalButton.BTN_MENU to listOf(109))
        )
        val t = IgmInputTranslator(withMenu)
        assertTrue(t.isMenuKey(109))
        assertFalse("the platform default is not this pad's menu button", t.isMenuKey(4))
    }

    @Test fun `with no mapping the platform's own menu keys are all there is to go on`() {
        val t = IgmInputTranslator(null)
        assertTrue(t.isMenuKey(4))
        assertTrue(t.isMenuKey(82))
        assertTrue(t.isMenuKey(110))
        assertFalse(t.isMenuKey(96))
    }

    @Test fun `back still passes through where the device binds nothing to it`() {
        assertEquals(MenuAction.BACK, IgmInputTranslator(retroid).normalize(4))
        assertEquals(MenuAction.BACK, IgmInputTranslator(null).normalize(4))
    }

    @Test fun standardLayoutMapsCorrectly() {
        val xbox = IgmInputMapping(
            buttonKeycodes = mapOf(
                CanonicalButton.BTN_SOUTH to listOf(96),
                CanonicalButton.BTN_EAST to listOf(97),
                CanonicalButton.BTN_WEST to listOf(99),
                CanonicalButton.BTN_NORTH to listOf(100),
            ),
            menuConfirm = CanonicalButton.BTN_SOUTH,
            menuBack = CanonicalButton.BTN_EAST,
        )
        val t = IgmInputTranslator(xbox)
        assertEquals(MenuAction.CONFIRM, t.normalize(96))
        assertEquals(MenuAction.BACK, t.normalize(97))
        assertEquals(MenuAction.WEST, t.normalize(99))
        assertEquals(MenuAction.NORTH, t.normalize(100))
    }

    // The same physical press means opposite things on the two layouts, which is the reason a
    // handler is given the action rather than the button.
    @Test fun `the same keycode confirms on one layout and goes back on the other`() {
        val xbox = retroid.copy(
            menuConfirm = CanonicalButton.BTN_SOUTH,
            menuBack = CanonicalButton.BTN_EAST,
        )
        assertEquals(MenuAction.CONFIRM, IgmInputTranslator(retroid).normalize(96))
        assertEquals(MenuAction.BACK, IgmInputTranslator(xbox).normalize(96))
    }

    @Test fun multipleKeycodesPerButton() {
        val m = retroid.copy(
            buttonKeycodes = retroid.buttonKeycodes + (CanonicalButton.BTN_WEST to listOf(100, 188))
        )
        val t = IgmInputTranslator(m)
        assertEquals(MenuAction.WEST, t.normalize(100))
        assertEquals(MenuAction.WEST, t.normalize(188))
    }

    // No profile is not no meaning: the conventional numbering is all there is to assume, and it is
    // what a pad launched outside Cannoli reports.
    @Test fun `with no mapping the conventional numbering is assumed`() {
        val t = IgmInputTranslator(null)
        assertEquals(MenuAction.CONFIRM, t.normalize(96))
        assertEquals(MenuAction.WEST, t.normalize(99))
        assertEquals(MenuAction.NORTH, t.normalize(100))
        assertEquals(MenuAction.UP, t.normalize(19))
    }

    // It used to arrive at the handlers as its own number, where it could match a branch by
    // sharing it. A key this pad has no meaning for now means nothing.
    @Test fun `an unrecognised keycode resolves to nothing`() {
        assertNull(IgmInputTranslator(retroid).normalize(4242))
    }

    /**
     * The whole point of the mapping: a pad that reports a button on an unconventional keycode has
     * to arrive at the same place as one that does not. Select used to fall through to its raw
     * value, so reordering a shader chain did nothing on any pad that numbered it differently.
     */
    @Test fun everyMappedButtonNormalizesRatherThanFallingThrough() {
        val expected = mapOf(
            CanonicalButton.BTN_UP to MenuAction.UP,
            CanonicalButton.BTN_DOWN to MenuAction.DOWN,
            CanonicalButton.BTN_LEFT to MenuAction.LEFT,
            CanonicalButton.BTN_RIGHT to MenuAction.RIGHT,
            CanonicalButton.BTN_WEST to MenuAction.WEST,
            CanonicalButton.BTN_NORTH to MenuAction.NORTH,
            CanonicalButton.BTN_L to MenuAction.L1,
            CanonicalButton.BTN_R to MenuAction.R1,
            CanonicalButton.BTN_L2 to MenuAction.L2,
            CanonicalButton.BTN_R2 to MenuAction.R2,
            CanonicalButton.BTN_L3 to MenuAction.L3,
            CanonicalButton.BTN_R3 to MenuAction.R3,
            CanonicalButton.BTN_START to MenuAction.START,
            CanonicalButton.BTN_SELECT to MenuAction.SELECT,
            CanonicalButton.BTN_MENU to MenuAction.MENU,
        )
        // Deliberately nothing like the conventional numbering, so a fallthrough cannot pass.
        val odd = expected.keys.withIndex().associate { (i, b) -> b to listOf(700 + i) }
        val t = IgmInputTranslator(
            IgmInputMapping(
                buttonKeycodes = odd + mapOf(
                    CanonicalButton.BTN_SOUTH to listOf(800),
                    CanonicalButton.BTN_EAST to listOf(801),
                ),
                menuConfirm = CanonicalButton.BTN_SOUTH,
                menuBack = CanonicalButton.BTN_EAST,
            )
        )
        for ((button, action) in expected) {
            assertEquals(button.name, action, t.normalize(odd.getValue(button).single()))
        }
        assertEquals(MenuAction.CONFIRM, t.normalize(800))
        assertEquals(MenuAction.BACK, t.normalize(801))
    }

    // Sticks are axes, so they carry no button meaning and must not be given one.
    @Test fun analogAxesAreNotTurnedIntoButtons() {
        val t = IgmInputTranslator(
            retroid.copy(
                buttonKeycodes = retroid.buttonKeycodes +
                    (CanonicalButton.BTN_LSTICK_X to listOf(900))
            )
        )
        assertNull(t.normalize(900))
    }
}
