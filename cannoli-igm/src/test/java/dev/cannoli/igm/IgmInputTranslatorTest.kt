package dev.cannoli.igm

import org.junit.Assert.assertEquals
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

    @Test fun westButtonNormalizesToFilterKeycode() {
        assertEquals(99, IgmInputTranslator(retroid).normalize(100))
    }

    @Test fun northButtonNormalizesToNorthKeycode() {
        assertEquals(100, IgmInputTranslator(retroid).normalize(99))
    }

    @Test fun confirmAndBackFollowMenuAssignment() {
        val t = IgmInputTranslator(retroid)
        assertEquals(96, t.normalize(96))
        assertEquals(97, t.normalize(97))
    }

    @Test fun shoulderButtonsNormalize() {
        val t = IgmInputTranslator(retroid)
        assertEquals(102, t.normalize(102))
        assertEquals(103, t.normalize(103))
    }

    @Test fun dpadAndSystemBackPassThrough() {
        val t = IgmInputTranslator(retroid)
        assertEquals(19, t.normalize(19))
        assertEquals(20, t.normalize(20))
        assertEquals(21, t.normalize(21))
        assertEquals(22, t.normalize(22))
        assertEquals(97, t.normalize(4))
    }

    // The Retroid Pocket Nova's menu button reports KEYCODE_BACK, and its cfg says so. Answering
    // from the pass-through first made that button back once you were inside the menu, so menu and
    // back were one key and nothing could be bound to menu.
    @Test fun `a menu button that reports back is still menu`() {
        val withMenu = retroid.copy(
            buttonKeycodes = retroid.buttonKeycodes + (CanonicalButton.BTN_MENU to listOf(4))
        )
        assertEquals(82, IgmInputTranslator(withMenu).normalize(4))
    }

    @Test fun `back still passes through where the device binds nothing to it`() {
        assertEquals(97, IgmInputTranslator(retroid).normalize(4))
        assertEquals(97, IgmInputTranslator(null).normalize(4))
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
        assertEquals(96, t.normalize(96))
        assertEquals(97, t.normalize(97))
        assertEquals(99, t.normalize(99))
        assertEquals(100, t.normalize(100))
    }

    @Test fun multipleKeycodesPerButton() {
        val m = retroid.copy(
            buttonKeycodes = retroid.buttonKeycodes + (CanonicalButton.BTN_WEST to listOf(100, 188))
        )
        val t = IgmInputTranslator(m)
        assertEquals(99, t.normalize(100))
        assertEquals(99, t.normalize(188))
    }

    @Test fun nullMappingIsIdentity() {
        val t = IgmInputTranslator(null)
        assertEquals(96, t.normalize(96))
        assertEquals(99, t.normalize(99))
        assertEquals(100, t.normalize(100))
        assertEquals(19, t.normalize(19))
    }

    @Test fun unknownKeycodePassesThrough() {
        assertEquals(4242, IgmInputTranslator(retroid).normalize(4242))
    }

    /**
     * The whole point of the mapping: a pad that reports a button on an unconventional keycode has
     * to arrive at the same place as one that does not. Select used to fall through to its raw
     * value, so reordering a shader chain did nothing on any pad that numbered it differently.
     */
    @Test fun everyMappedButtonNormalizesRatherThanFallingThrough() {
        val expected = mapOf(
            CanonicalButton.BTN_UP to 19,
            CanonicalButton.BTN_DOWN to 20,
            CanonicalButton.BTN_LEFT to 21,
            CanonicalButton.BTN_RIGHT to 22,
            CanonicalButton.BTN_WEST to 99,
            CanonicalButton.BTN_NORTH to 100,
            CanonicalButton.BTN_L to 102,
            CanonicalButton.BTN_R to 103,
            CanonicalButton.BTN_L2 to 104,
            CanonicalButton.BTN_R2 to 105,
            CanonicalButton.BTN_L3 to 106,
            CanonicalButton.BTN_R3 to 107,
            CanonicalButton.BTN_START to 108,
            CanonicalButton.BTN_SELECT to 109,
            CanonicalButton.BTN_MENU to 82,
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
        for ((button, code) in expected) {
            assertEquals(button.name, code, t.normalize(odd.getValue(button).single()))
        }
        assertEquals(96, t.normalize(800))
        assertEquals(97, t.normalize(801))
    }

    // Sticks are axes, so they carry no keycode meaning and must not be given one.
    @Test fun analogAxesAreNotTurnedIntoButtons() {
        val t = IgmInputTranslator(
            retroid.copy(
                buttonKeycodes = retroid.buttonKeycodes +
                    (CanonicalButton.BTN_LSTICK_X to listOf(900))
            )
        )
        assertEquals(900, t.normalize(900))
    }
}
