package dev.cannoli.scorza.input.legend

import dev.cannoli.igm.CanonicalButton
import dev.cannoli.scorza.input.DeviceMapping
import dev.cannoli.scorza.input.DeviceMatchRule
import dev.cannoli.scorza.input.InputBinding
import dev.cannoli.scorza.input.MappingSource
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WizardTriggerTest {

    private fun mapping(
        source: MappingSource,
        menuConfirm: CanonicalButton = CanonicalButton.BTN_SOUTH,
        bindings: Map<CanonicalButton, List<InputBinding>> = mapOf(
            CanonicalButton.BTN_SOUTH to listOf(InputBinding.Button(96)),
            CanonicalButton.BTN_EAST to listOf(InputBinding.Button(97)),
        ),
    ) = DeviceMapping(
        id = "test_pad",
        displayName = "Test Pad",
        match = DeviceMatchRule(
            name = "Test Pad",
            vendorId = 1234,
            productId = 5678,
        ),
        bindings = bindings,
        menuConfirm = menuConfirm,
        source = source,
    )

    @Test fun `an unidentified pad runs the wizard whatever shape it is`() {
        assertTrue(shouldRunLegendWizard(mapping(MappingSource.ANDROID_DEFAULT)))
        assertTrue(
            shouldRunLegendWizard(
                mapping(
                    MappingSource.ANDROID_DEFAULT,
                    bindings = mapOf(CanonicalButton.BTN_SOUTH to listOf(InputBinding.Button(96))),
                )
            )
        )
    }

    @Test fun `an identified pad does not run the wizard`() {
        assertFalse(shouldRunLegendWizard(mapping(MappingSource.RETROARCH_AUTOCONFIG)))
        assertFalse(shouldRunLegendWizard(mapping(MappingSource.USER_WIZARD)))
    }

    @Test fun `the expected confirm keycode verifies the pad`() {
        assertTrue(verifyConfirmPress(mapping(MappingSource.RETROARCH_AUTOCONFIG), keyCode = 96))
    }

    @Test fun `a confirm on the east position verifies its own keycode`() {
        val m = mapping(MappingSource.RETROARCH_AUTOCONFIG, menuConfirm = CanonicalButton.BTN_EAST)
        assertTrue(verifyConfirmPress(m, keyCode = 97))
        assertFalse(verifyConfirmPress(m, keyCode = 96))
    }

    @Test fun `any other button fails verification and runs the wizard`() {
        val m = mapping(MappingSource.RETROARCH_AUTOCONFIG)
        assertFalse(verifyConfirmPress(m, keyCode = 97))
        assertFalse(verifyConfirmPress(m, keyCode = 108))
    }

    @Test fun `an unidentified pad never verifies`() {
        assertFalse(verifyConfirmPress(mapping(MappingSource.ANDROID_DEFAULT), keyCode = 96))
    }

    @Test fun `a mapping that binds nothing to confirm never verifies`() {
        val m = mapping(
            MappingSource.RETROARCH_AUTOCONFIG,
            bindings = mapOf(CanonicalButton.BTN_EAST to listOf(InputBinding.Button(97))),
        )
        assertFalse(verifyConfirmPress(m, keyCode = 96))
    }

    @Test fun `every keycode bound to confirm verifies`() {
        val m = mapping(
            MappingSource.RETROARCH_AUTOCONFIG,
            bindings = mapOf(
                CanonicalButton.BTN_SOUTH to listOf(InputBinding.Button(96), InputBinding.Button(188)),
            ),
        )
        assertTrue(verifyConfirmPress(m, keyCode = 96))
        assertTrue(verifyConfirmPress(m, keyCode = 188))
    }
}
