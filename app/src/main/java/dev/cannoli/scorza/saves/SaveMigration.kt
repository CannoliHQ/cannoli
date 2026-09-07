package dev.cannoli.scorza.saves

import dev.cannoli.scorza.config.CannoliPaths
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Moves a game's loose save files into its own folder.
 *
 * Saves were the one per-content artifact Cannoli did not key by [dev.cannoli.core.RomKey], which
 * left three call sites independently stem-matching over the platform directory and all three
 * blind to a directory save. One folder per game removes the matching entirely.
 *
 * Attribution comes from the caller, which knows the rom rows. Nothing here parses a filename to
 * decide who owns a save: a file that belongs to no game is left exactly where it is, because
 * moving something we cannot attribute is how a migration destroys data it did not understand.
 */
class SaveMigration(
    private val paths: CannoliPaths,
    private val now: () -> Long = System::currentTimeMillis,
) {
    enum class Outcome { MOVED, NOTHING_TO_DO, BACKUP_FAILED, COLLIDED, MOVE_FAILED }

    data class Result(val outcome: Outcome, val moved: Int = 0)

    /**
     * Idempotent. Safe to call on every launch: a game already migrated has no loose files left to
     * find and answers [Outcome.NOTHING_TO_DO] without touching the card.
     */
    fun migrateGame(tag: String, romBaseName: String): Result {
        val platformDir = paths.savesFor(tag)
        val loose = looseFilesFor(platformDir, romBaseName)
        if (loose.isEmpty()) return Result(Outcome.NOTHING_TO_DO)

        val target = paths.saveDirFor(tag, romBaseName)
        // A file already sitting at the destination is something this migration did not predict.
        // Leaving both and saying so beats overwriting a save on a guess.
        val collisions = loose.filter { File(target, it.name).exists() }
        if (collisions.isNotEmpty()) {
            log("collision $tag/$romBaseName: ${collisions.joinToString { it.name }}")
            return Result(Outcome.COLLIDED)
        }

        if (!backup(tag, romBaseName, loose)) {
            log("backup failed $tag/$romBaseName, left in place")
            return Result(Outcome.BACKUP_FAILED)
        }

        if (!target.isDirectory && !target.mkdirs()) {
            log("mkdir failed ${target.absolutePath}")
            return Result(Outcome.MOVE_FAILED)
        }

        var moved = 0
        for (file in loose) {
            val dest = File(target, file.name)
            // Never delete a source that did not move. A half-migrated game keeps every byte it
            // had, loose and readable, and the resolver still finds it.
            if (file.renameTo(dest)) {
                moved++
                log("${file.absolutePath} -> ${dest.absolutePath}")
            } else {
                log("move failed ${file.absolutePath}")
            }
        }
        return if (moved == loose.size) Result(Outcome.MOVED, moved) else Result(Outcome.MOVE_FAILED, moved)
    }

    /**
     * The loose files a game owns. Directories are excluded: the per-game folder is the
     * destination, and a directory save is already in the shape this produces.
     */
    private fun looseFilesFor(platformDir: File, romBaseName: String): List<File> =
        platformDir.listFiles().orEmpty().filter {
            it.isFile && (it.nameWithoutExtension == romBaseName || it.name.startsWith("$romBaseName."))
        }

    private fun backup(tag: String, romBaseName: String, files: List<File>): Boolean = try {
        val dest = File(backupRoot(), "$tag/$romBaseName").apply { mkdirs() }
        files.forEach { it.copyTo(File(dest, it.name), overwrite = true) }
        true
    } catch (_: Exception) {
        false
    }

    private val stamp: String by lazy {
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(now()))
    }

    private fun backupRoot(): File = File(paths.backupDir, "save-migration-$stamp")

    private fun log(line: String) {
        try {
            val file = File(paths.configState, MANIFEST).apply { parentFile?.mkdirs() }
            file.appendText("$line\n")
        } catch (_: Exception) {}
    }

    private companion object {
        const val MANIFEST = "save_migration.log"
    }
}
