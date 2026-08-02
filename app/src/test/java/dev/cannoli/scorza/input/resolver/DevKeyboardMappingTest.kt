package dev.cannoli.scorza.input.resolver

import android.view.KeyEvent
import dev.cannoli.scorza.input.CanonicalButton
import dev.cannoli.scorza.input.ConnectedDevice
import dev.cannoli.scorza.input.InputBinding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DevKeyboardMappingTest {

    private val device = ConnectedDevice(
        androidDeviceId = 4,
        descriptor = "kbd-1",
        name = "AVD Keyboard",
        vendorId = 0,
        productId = 0,
        androidBuildModel = "sdk_gphone64_arm64",
        sourceMask = 0,
        connectedAtMillis = 0L,
    )

    @Test
    fun every_canonical_button_is_reachable_from_the_keyboard() {
        val missing = CanonicalButton.entries.filter { it !in DevKeyboardMapping.BINDINGS }
        assertTrue("unbound canonicals: $missing", missing.isEmpty())
    }

    @Test
    fun no_keycode_drives_two_different_buttons() {
        val duplicates = DevKeyboardMapping.BINDINGS.values
            .flatten()
            .groupingBy { it }
            .eachCount()
            .filterValues { it > 1 }
        assertTrue("keycodes bound more than once: ${duplicates.keys}", duplicates.isEmpty())
    }

    @Test
    fun enter_confirms_and_escape_goes_back() {
        val mapping = DevKeyboardMapping.create(device)

        assertEquals(CanonicalButton.BTN_SOUTH, mapping.menuConfirm)
        assertEquals(CanonicalButton.BTN_EAST, mapping.menuBack)
        assertTrue(
            mapping.bindings[CanonicalButton.BTN_SOUTH]
                ?.contains(InputBinding.Button(KeyEvent.KEYCODE_ENTER)) == true
        )
        assertTrue(
            mapping.bindings[CanonicalButton.BTN_EAST]
                ?.contains(InputBinding.Button(KeyEvent.KEYCODE_ESCAPE)) == true
        )
    }

    @Test
    fun back_keycode_also_goes_back_because_some_avd_layouts_send_it_for_escape() {
        val mapping = DevKeyboardMapping.create(device)

        assertTrue(
            mapping.bindings[CanonicalButton.BTN_EAST]
                ?.contains(InputBinding.Button(KeyEvent.KEYCODE_BACK)) == true
        )
    }

    @Test
    fun arrow_keys_drive_navigation() {
        val mapping = DevKeyboardMapping.create(device)
        val expected = mapOf(
            CanonicalButton.BTN_UP to KeyEvent.KEYCODE_DPAD_UP,
            CanonicalButton.BTN_DOWN to KeyEvent.KEYCODE_DPAD_DOWN,
            CanonicalButton.BTN_LEFT to KeyEvent.KEYCODE_DPAD_LEFT,
            CanonicalButton.BTN_RIGHT to KeyEvent.KEYCODE_DPAD_RIGHT,
        )

        for ((button, keyCode) in expected) {
            assertEquals(listOf(InputBinding.Button(keyCode)), mapping.bindings[button])
        }
    }

    @Test
    fun mapping_is_not_marked_user_edited_so_it_can_never_overwrite_a_real_one() {
        val mapping = DevKeyboardMapping.create(device)

        assertEquals(DevKeyboardMapping.ID, mapping.id)
        assertEquals(false, mapping.userEdited)
    }
}
