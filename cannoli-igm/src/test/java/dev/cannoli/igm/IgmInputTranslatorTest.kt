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
}
