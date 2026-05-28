package dev.cannoli.scorza.libretro

import java.io.File

enum class CoreQuirk {
    // The core's disk_control callbacks dereference uninitialized state when
    // polled from outside retro_run, crashing whichever thread invokes them.
    // Affected cores skip refreshDiskInfo on the menu path.
    UNSAFE_DISK_CONTROL_OUTSIDE_RETRO_RUN,
}

object CoreQuirks {
    private val table: Map<String, Set<CoreQuirk>> = mapOf(
        "mednafen_pce_fast" to setOf(CoreQuirk.UNSAFE_DISK_CONTROL_OUTSIDE_RETRO_RUN),
    )

    fun has(corePath: String, quirk: CoreQuirk): Boolean {
        if (corePath.isEmpty()) return false
        val name = File(corePath).nameWithoutExtension.lowercase()
        return table.entries.any { (key, quirks) -> key in name && quirk in quirks }
    }
}
