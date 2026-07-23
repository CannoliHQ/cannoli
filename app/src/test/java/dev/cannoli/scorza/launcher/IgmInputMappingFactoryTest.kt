package dev.cannoli.scorza.launcher

import dev.cannoli.igm.CanonicalButton
import dev.cannoli.scorza.input.DeviceMapping
import dev.cannoli.scorza.input.DeviceMatchRule
import dev.cannoli.scorza.input.HatDirection
import dev.cannoli.scorza.input.InputBinding
import dev.cannoli.scorza.input.MappingSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IgmInputMappingFactoryTest {

    @Test fun `null mapping returns null`() {
        assertNull((null as DeviceMapping?).toIgmInputMapping())
    }

    @Test fun `button keycodes are kept, hat-only buttons are dropped, menu buttons are carried`() {
        val mapping = DeviceMapping(
            id = "test-device",
            displayName = "Test Device",
            match = DeviceMatchRule(name = "Test Device"),
            bindings = mapOf(
                CanonicalButton.BTN_WEST to listOf(InputBinding.Button(100)),
                CanonicalButton.BTN_EAST to listOf(InputBinding.Button(96)),
                CanonicalButton.BTN_UP to listOf(InputBinding.Hat(axis = 1, direction = HatDirection.UP)),
            ),
            menuConfirm = CanonicalButton.BTN_EAST,
            menuBack = CanonicalButton.BTN_SOUTH,
            source = MappingSource.BUNDLED,
        )

        val result = mapping.toIgmInputMapping()!!

        assertEquals(listOf(100), result.buttonKeycodes[CanonicalButton.BTN_WEST])
        assertEquals(listOf(96), result.buttonKeycodes[CanonicalButton.BTN_EAST])
        assertNull(result.buttonKeycodes[CanonicalButton.BTN_UP])
        assertEquals(CanonicalButton.BTN_EAST, result.menuConfirm)
        assertEquals(CanonicalButton.BTN_SOUTH, result.menuBack)
    }

    @Test fun `mixed bindings keep only button keycodes for a given canonical button`() {
        val mapping = DeviceMapping(
            id = "mixed-device",
            displayName = "Mixed Device",
            match = DeviceMatchRule(),
            bindings = mapOf(
                CanonicalButton.BTN_NORTH to listOf(
                    InputBinding.Button(99),
                    InputBinding.Hat(axis = 0, direction = HatDirection.LEFT),
                ),
            ),
            menuConfirm = CanonicalButton.BTN_EAST,
            menuBack = CanonicalButton.BTN_SOUTH,
            source = MappingSource.USER_WIZARD,
        )

        val result = mapping.toIgmInputMapping()!!

        assertEquals(listOf(99), result.buttonKeycodes[CanonicalButton.BTN_NORTH])
    }
}
