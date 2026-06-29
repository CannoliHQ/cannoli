package dev.cannoli.scorza.romm.sync

import dev.cannoli.scorza.config.PlatformConfig
import dev.cannoli.scorza.model.Rom
import java.io.File
import java.text.Normalizer

fun rommResolveGame(platformResolver: PlatformConfig, romDir: File): (String) -> Triple<String, String, String?>? = { gameKey ->
    val romFile = File(romDir, gameKey)
    if (!romFile.exists()) {
        null
    } else {
        val tag = gameKey.substringBefore('/')
        val base = Normalizer.normalize(File(gameKey).nameWithoutExtension, Normalizer.Form.NFC)
        val rom = Rom(id = 0, path = romFile, platformTag = tag, displayName = base)
        val emulator = RomKeys.coreDisplayNameFor(rom, platformResolver)
        Triple(tag, base, emulator)
    }
}
