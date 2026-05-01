package dev.cannoli.scorza.input.v2.runtime

import dev.cannoli.scorza.input.v2.DeviceMapping
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ActiveMappingHolder {

    private val _active = MutableStateFlow<DeviceMapping?>(null)
    val active: StateFlow<DeviceMapping?> = _active.asStateFlow()

    fun set(mapping: DeviceMapping) {
        _active.value = mapping
    }

    fun clear() {
        _active.value = null
    }
}

fun DeviceMapping?.confirmButton(): dev.cannoli.ui.ConfirmButton =
    if (this?.menuConfirm == dev.cannoli.scorza.input.v2.CanonicalButton.BTN_EAST) dev.cannoli.ui.ConfirmButton.EAST else dev.cannoli.ui.ConfirmButton.SOUTH

fun DeviceMapping?.labelSet(fallback: dev.cannoli.ui.ButtonLabelSet): dev.cannoli.ui.ButtonLabelSet =
    when (this?.glyphStyle) {
        dev.cannoli.scorza.input.v2.GlyphStyle.PLUMBER -> dev.cannoli.ui.ButtonLabelSet.PLUMBER
        dev.cannoli.scorza.input.v2.GlyphStyle.REDMOND -> dev.cannoli.ui.ButtonLabelSet.REDMOND
        dev.cannoli.scorza.input.v2.GlyphStyle.SHAPES -> dev.cannoli.ui.ButtonLabelSet.SHAPES
        null -> fallback
    }
