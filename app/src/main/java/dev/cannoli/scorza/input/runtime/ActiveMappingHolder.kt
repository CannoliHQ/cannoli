package dev.cannoli.scorza.input.runtime

import dev.cannoli.scorza.input.DeviceMapping
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

fun DeviceMapping?.confirmButton(): dev.cannoli.ui.ConfirmButton = when {
    this == null -> dev.cannoli.ui.ConfirmButton.EAST
    this.menuConfirm == dev.cannoli.scorza.input.CanonicalButton.BTN_EAST -> dev.cannoli.ui.ConfirmButton.EAST
    else -> dev.cannoli.ui.ConfirmButton.SOUTH
}

fun DeviceMapping?.labelSet(fallback: dev.cannoli.ui.ButtonLabelSet): dev.cannoli.ui.ButtonLabelSet =
    when (this?.glyphStyle) {
        dev.cannoli.scorza.input.GlyphStyle.PLUMBER -> dev.cannoli.ui.ButtonLabelSet.PLUMBER
        dev.cannoli.scorza.input.GlyphStyle.REDMOND -> dev.cannoli.ui.ButtonLabelSet.REDMOND
        dev.cannoli.scorza.input.GlyphStyle.SHAPES -> dev.cannoli.ui.ButtonLabelSet.SHAPES
        dev.cannoli.scorza.input.GlyphStyle.HEDGEHOG_6 -> dev.cannoli.ui.ButtonLabelSet.HEDGEHOG_6
        null -> fallback
    }
