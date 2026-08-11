package dev.cannoli.core

import java.io.File
import java.text.Normalizer

/**
 * The canonical key for a game's per-content directories (guides, cheats, save states): the
 * ROM's raw base filename, NFC-normalized. A bundled multi-disc game's path is already the
 * generated <name>.m3u rather than a disc file, so this is bundle-level with no extra handling.
 * The launcher, the IGM and Kitchen all key on this so none of them can resolve a different
 * directory for the same game.
 */
object RomKey {
    fun baseName(path: File): String = normalize(path.nameWithoutExtension)

    fun normalize(name: String): String = Normalizer.normalize(name, Normalizer.Form.NFC)
}
