package dev.cannoli.scorza.saves

import dev.cannoli.core.RomKey
import dev.cannoli.scorza.config.CannoliPaths
import dev.cannoli.scorza.db.RomsRepository
import dev.cannoli.scorza.di.CannoliPathsProvider
import dev.cannoli.scorza.util.ScanLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Moves every game's loose saves into its own folder, once, in the background.
 *
 * Attribution comes from the rom rows rather than from the file names in the save directory: a
 * save whose game is in the library moves, and anything else is left exactly where it is. The
 * launch path migrates the game about to run regardless, so this is a catch-up pass for the rest
 * of the library rather than the mechanism the correctness of a launch depends on.
 */
@Singleton
class SaveMigrationSweep @Inject constructor(
    private val pathsProvider: CannoliPathsProvider,
    private val romsRepository: RomsRepository,
) {
    private val paths: CannoliPaths get() = CannoliPaths(pathsProvider.root)

    suspend fun runOnce(): Int = withContext(Dispatchers.IO) {
        val marker = File(paths.configState, MARKER)
        if (marker.exists()) return@withContext 0

        val migration = SaveMigration(paths)
        var migrated = 0
        val roms = try { romsRepository.allRoms() } catch (_: Exception) { return@withContext 0 }
        for (rom in roms) {
            yield()
            val outcome = try {
                migration.migrateGame(rom.platformTag, RomKey.baseName(rom.path))
            } catch (_: Exception) {
                continue
            }
            if (outcome.outcome == SaveMigration.Outcome.MOVED) migrated++
        }

        // Written only after a full pass. A run cut short by the launcher going away resumes from
        // the top next time, which costs a directory listing per game and moves nothing twice.
        try {
            marker.parentFile?.mkdirs()
            marker.writeText("1")
        } catch (_: Exception) {}
        if (migrated > 0) ScanLog.write("saves: migrated $migrated games into their own folders")
        migrated
    }

    private companion object {
        const val MARKER = ".saves_migrated"
    }
}
