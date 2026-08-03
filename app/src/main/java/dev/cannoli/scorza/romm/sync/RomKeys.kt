package dev.cannoli.scorza.romm.sync

import dev.cannoli.scorza.config.PlatformConfig
import dev.cannoli.scorza.model.Rom
import java.io.File

object RomKeys {
    fun relativeKey(rom: File, romRoot: File): String =
        rom.absolutePath.removePrefix(romRoot.absolutePath).removePrefix(File.separator)

    // ifEmpty matters: a standalone override stores an empty coreId, which is non-null, so the
    // elvis never fired and the Save Slots header rendered a blank emulator name.
    fun coreDisplayNameFor(
        rom: Rom,
        platformConfig: PlatformConfig,
        override: dev.cannoli.scorza.config.EmulatorChoice?,
    ): String? {
        val coreId = override?.coreId?.ifEmpty { null }
            ?: platformConfig.getCoreName(rom.platformTag) ?: return null
        return platformConfig.getCoreDisplayName(coreId)
    }
}
