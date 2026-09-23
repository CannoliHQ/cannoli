package dev.cannoli.ricotta

import dev.cannoli.igm.MachineValue

internal fun decodeMoved(pairs: Array<String>): Map<String, MachineValue> =
    pairs.toList().chunked(2).filter { it.size == 2 }.associate { it[0] to MachineValue(it[1]) }
