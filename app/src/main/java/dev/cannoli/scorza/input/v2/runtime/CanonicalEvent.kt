package dev.cannoli.scorza.input.v2.runtime

import dev.cannoli.scorza.input.v2.AnalogRole
import dev.cannoli.scorza.input.v2.CanonicalButton

sealed interface CanonicalEvent {
    data class Pressed(val button: CanonicalButton) : CanonicalEvent
    data class Released(val button: CanonicalButton) : CanonicalEvent
    data class AnalogChanged(val role: AnalogRole, val value: Float) : CanonicalEvent
}
