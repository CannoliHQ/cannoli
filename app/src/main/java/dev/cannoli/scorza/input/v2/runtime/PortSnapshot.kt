package dev.cannoli.scorza.input.v2.runtime

import dev.cannoli.scorza.input.v2.AnalogRole
import dev.cannoli.scorza.input.v2.CanonicalButton

data class PortSnapshot(
    val pressed: Set<CanonicalButton>,
    val analog: Map<AnalogRole, Float>,
)
