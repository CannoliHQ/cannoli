package dev.cannoli.scorza.launcher

import dev.cannoli.scorza.config.CannoliPaths
import dev.cannoli.scorza.libretro.SaveSlotManager
import dev.cannoli.scorza.util.ArchiveExtractor
import dev.cannoli.scorza.util.AtomicRename
import java.io.File
import java.text.Normalizer

// Heals pre-fix save data for archived games whose on-disk identity is the
// archive's inner entry name rather than the library (zip) name. Lazy: runs at
// launch, short-circuits once library-named data exists. Sunset once the early
// user base has rolled forward - delete this class and its launchEmbedded call.
class SaveIdentityMigrator(private val cannoliRoot: File) {

    private val paths = CannoliPaths(cannoliRoot)

    fun migrateOnLaunch(platformTag: String, libraryBaseName: String, archive: File) {
        if (libraryDataExists(platformTag, libraryBaseName)) return

        val innerRaw = ArchiveExtractor.primaryEntryName(archive) ?: return
        val innerBase = Normalizer.normalize(File(innerRaw).nameWithoutExtension, Normalizer.Form.NFC)
        if (innerBase == libraryBaseName) return

        AtomicRename(cannoliRoot).relocateSaveData(platformTag, innerBase, libraryBaseName)
    }

    private fun libraryDataExists(tag: String, base: String): Boolean {
        val slots = SaveSlotManager(paths.saveStateBase(tag, base).absolutePath)
        if (slots.slots.any { slots.stateExists(it) }) return true
        return paths.sramFile(tag, base).exists()
    }
}
