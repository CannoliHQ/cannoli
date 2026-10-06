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
