package dev.cannoli.scorza.romm.sync

import dev.cannoli.scorza.config.PlatformConfig
import dev.cannoli.scorza.model.Rom
import java.io.File

object RomKeys {
    fun relativeKey(rom: File, romRoot: File): String =
        rom.absolutePath.removePrefix(romRoot.absolutePath).removePrefix(File.separator)

    fun coreDisplayNameFor(rom: Rom, platformConfig: PlatformConfig): String? {
        val override = platformConfig.getGameOverride(rom.path.absolutePath)
        val coreId = override?.coreId ?: platformConfig.getCoreName(rom.platformTag) ?: return null
        return platformConfig.getCoreDisplayName(coreId)
    }
}
