package dev.cannoli.scorza.launcher

import dev.cannoli.igm.IgmInputMapping
import dev.cannoli.scorza.input.DeviceMapping
import dev.cannoli.scorza.input.InputBinding

/**
 * Build the IGM's input mapping from a device mapping: the raw Android keycodes for each
 * canonical button's `Button` bindings, plus the menu confirm/back assignment. Hat and axis
 * bindings are dropped - the IGM relies on Android's hat->DPAD conversion and uses no analog.
 */
fun DeviceMapping?.toIgmInputMapping(): IgmInputMapping? {
    val mapping = this ?: return null
    val buttonKeycodes = mapping.bindings
        .mapValues { (_, bindings) ->
            bindings.filterIsInstance<InputBinding.Button>().map { it.keyCode }
        }
        .filterValues { it.isNotEmpty() }
    return IgmInputMapping(
        buttonKeycodes = buttonKeycodes,
        menuConfirm = mapping.menuConfirm,
        menuBack = mapping.menuBack,
    )
}
