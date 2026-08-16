package dev.cannoli.scorza.input.legend

import dev.cannoli.scorza.input.DeviceMapping
import dev.cannoli.scorza.input.InputBinding
import dev.cannoli.scorza.input.MappingSource

// A pad gets the legend wizard when Cannoli could not identify it, so its bindings are Android's
// raw defaults. The wizard asks what each button does rather than where it sits, so every pad shape
// can answer it and nothing else gates entry.
fun shouldRunLegendWizard(mapping: DeviceMapping): Boolean =
    mapping.source == MappingSource.ANDROID_DEFAULT

/**
 * First run verifies the pad instead of assuming it: the button the user pressed a run of times has
 * to be the one the resolved mapping calls confirm. Anything else, or a pad Cannoli never
 * identified, is the second cause for running the wizard.
 */
fun verifyConfirmPress(mapping: DeviceMapping, keyCode: Int): Boolean =
    !shouldRunLegendWizard(mapping) && keyCode in confirmKeyCodes(mapping)

private fun confirmKeyCodes(mapping: DeviceMapping): List<Int> =
    mapping.bindings[mapping.menuConfirm]
        .orEmpty()
        .filterIsInstance<InputBinding.Button>()
        .map { it.keyCode }
