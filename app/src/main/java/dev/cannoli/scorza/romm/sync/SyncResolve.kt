package dev.cannoli.scorza.romm.sync

import dev.cannoli.scorza.config.PlatformConfig
import dev.cannoli.scorza.model.Rom
import java.io.File
import java.text.Normalizer

// overrideFor resolves a per-game emulator override from the relative game key. Overrides are
// keyed by rom_id, and the Rom built here is synthetic, so the lookup has to go through the
// caller rather than through the synthetic id.
fun rommResolveGame(
    platformResolver: PlatformConfig,
    romDir: File,
    overrideFor: (String) -> dev.cannoli.scorza.config.EmulatorChoice? = { null },
): (String) -> Triple<String, String, String?>? = { gameKey ->
    val romFile = File(romDir, gameKey)
    if (!romFile.exists()) {
        null
    } else {
        val tag = gameKey.substringBefore('/')
        val base = Normalizer.normalize(File(gameKey).nameWithoutExtension, Normalizer.Form.NFC)
        val rom = Rom(id = 0, path = romFile, platformTag = tag, displayName = base)
        val emulator = RomKeys.coreDisplayNameFor(rom, platformResolver, overrideFor(gameKey))
        Triple(tag, base, emulator)
    }
}
