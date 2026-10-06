package dev.cannoli.scorza.launcher

import dev.cannoli.igm.IgmInputMapping
import dev.cannoli.scorza.input.DeviceMapping
import dev.cannoli.scorza.input.InputBinding

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
