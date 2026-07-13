package dev.cannoli.scorza.util

import android.view.KeyEvent
import dev.cannoli.scorza.input.CanonicalButton
import dev.cannoli.scorza.input.DeviceMapping
import dev.cannoli.scorza.input.DeviceMatchRule
import dev.cannoli.scorza.input.GlyphStyle
import dev.cannoli.scorza.input.HatDirection
import dev.cannoli.scorza.input.InputBinding
import dev.cannoli.scorza.input.MappingSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ButtonLabelTest {

    /** Start and Select swapped, the way a user who wants Start on top would configure it. */
    private fun flippedStartSelect() = DeviceMapping(
        id = "flipped",
        displayName = "Retroid Pocket Classic",
        match = DeviceMatchRule(),
        bindings = mapOf(
            CanonicalButton.BTN_START to listOf(InputBinding.Button(KeyEvent.KEYCODE_BUTTON_SELECT)),
            CanonicalButton.BTN_SELECT to listOf(InputBinding.Button(KeyEvent.KEYCODE_BUTTON_START)),
            CanonicalButton.BTN_SOUTH to listOf(InputBinding.Button(KeyEvent.KEYCODE_BUTTON_A)),
            CanonicalButton.BTN_UP to listOf(
                InputBinding.Hat(android.view.MotionEvent.AXIS_HAT_Y, HatDirection.UP),
            ),
        ),
        glyphStyle = GlyphStyle.PLUMBER,
        source = MappingSource.USER_WIZARD,
        userEdited = true,
    )

    @Test
    fun `a remapped key resolves to the canonical button the user configured`() {
        val mapping = flippedStartSelect()

        assertEquals(
            CanonicalButton.BTN_START,
            canonicalForKeyCode(KeyEvent.KEYCODE_BUTTON_SELECT, mapping),
        )
        assertEquals(
            CanonicalButton.BTN_SELECT,
            canonicalForKeyCode(KeyEvent.KEYCODE_BUTTON_START, mapping),
        )
    }

    @Test
    fun `an unremapped key resolves to its usual canonical button`() {
        assertEquals(
            CanonicalButton.BTN_SOUTH,
            canonicalForKeyCode(KeyEvent.KEYCODE_BUTTON_A, flippedStartSelect()),
        )
    }

    @Test
    fun `a key the mapping does not bind as a button has no canonical`() {
        // The D-pad here is a hat, so its synthesized keycode is not a Button binding and keeps
        // its hardware name.
        assertNull(canonicalForKeyCode(KeyEvent.KEYCODE_DPAD_UP, flippedStartSelect()))
        assertNull(canonicalForKeyCode(KeyEvent.KEYCODE_BUTTON_L2, flippedStartSelect()))
    }

    @Test
    fun `no mapping means no canonical`() {
        assertNull(canonicalForKeyCode(KeyEvent.KEYCODE_BUTTON_SELECT, null))
    }
}
