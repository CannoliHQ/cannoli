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

    private fun mapping(source: MappingSource) = DeviceMapping(
        id = "test_pad",
        displayName = "Test Pad",
        match = DeviceMatchRule(
            name = "Test Pad",
            vendorId = 1234,
            productId = 5678,
            descriptor = "abc123",
        ),
        bindings = mapOf(
            CanonicalButton.BTN_SOUTH to listOf(InputBinding.Button(96)),
        ),
        source = source,
    )

    @Test fun `android default with standard face codes runs the wizard`() {
        assertTrue(shouldRunLegendWizard(mapping(MappingSource.ANDROID_DEFAULT), hasStandardFaceCodes = true))
    }

    @Test fun `android default without standard face codes skips the wizard`() {
        assertFalse(shouldRunLegendWizard(mapping(MappingSource.ANDROID_DEFAULT), hasStandardFaceCodes = false))
    }

    @Test fun `identified source with standard face codes skips the wizard`() {
        assertFalse(shouldRunLegendWizard(mapping(MappingSource.RETROARCH_AUTOCONFIG), hasStandardFaceCodes = true))
    }
}
