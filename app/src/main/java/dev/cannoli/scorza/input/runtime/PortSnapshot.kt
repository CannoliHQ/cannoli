package dev.cannoli.scorza.input.runtime

import dev.cannoli.scorza.input.CanonicalButton

data class PortSnapshot(
    val pressed: Set<CanonicalButton>,
    val analog: Map<CanonicalButton, Float>,
)
