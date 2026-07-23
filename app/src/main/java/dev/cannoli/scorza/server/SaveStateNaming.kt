package dev.cannoli.scorza.server

internal fun raStateName(romName: String, slot: Int): String = when (slot) {
    0 -> "$romName.state.auto"
    1 -> "$romName.state"
    else -> "$romName.state${slot - 1}"
}
