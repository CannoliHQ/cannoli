package dev.cannoli.scorza.input.legend

import dev.cannoli.scorza.input.DeviceMapping
import dev.cannoli.scorza.input.InputBinding
import dev.cannoli.scorza.input.MappingSource

fun shouldRunLegendWizard(mapping: DeviceMapping): Boolean =
    mapping.source == MappingSource.UNIDENTIFIED

fun verifyConfirmPress(mapping: DeviceMapping, keyCode: Int): Boolean =
    !shouldRunLegendWizard(mapping) && keyCode in confirmKeyCodes(mapping)

private fun confirmKeyCodes(mapping: DeviceMapping): List<Int> =
    mapping.bindings[mapping.menuConfirm]
        .orEmpty()
        .filterIsInstance<InputBinding.Button>()
        .map { it.keyCode }
