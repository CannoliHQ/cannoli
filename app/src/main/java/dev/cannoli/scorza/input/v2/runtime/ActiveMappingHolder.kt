package dev.cannoli.scorza.input.v2.runtime

import dev.cannoli.scorza.input.v2.DeviceTemplate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ActiveTemplateHolder {

    private val _active = MutableStateFlow<DeviceTemplate?>(null)
    val active: StateFlow<DeviceTemplate?> = _active.asStateFlow()

    fun set(template: DeviceTemplate) {
        _active.value = template
    }

    fun clear() {
        _active.value = null
    }
}
