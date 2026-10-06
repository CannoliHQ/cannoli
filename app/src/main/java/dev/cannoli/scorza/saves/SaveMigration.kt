package dev.cannoli.scorza.saves

import dev.cannoli.scorza.config.CannoliPaths
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SaveMigration(
    private val paths: CannoliPaths,
    private val now: () -> Long = System::currentTimeMillis,
) {
    enum class Outcome { MOVED, NOTHING_TO_DO, BACKUP_FAILED, COLLIDED, MOVE_FAILED }

    data class Result(val outcome: Outcome, val moved: Int = 0)

    fun migrateGame(tag: String, romBaseName: String): Result {
        if (SharedSaveRoots.isShared(tag)) return Result(Outcome.NOTHING_TO_DO)
        val platformDir = paths.savesFor(tag)
        val loose = looseFilesFor(platformDir, romBaseName)
        if (loose.isEmpty()) return Result(Outcome.NOTHING_TO_DO)

        val target = paths.saveDirFor(tag, romBaseName)
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
            if (file.renameTo(dest)) {
                moved++
                log("${file.absolutePath} -> ${dest.absolutePath}")
            } else {
                log("move failed ${file.absolutePath}")
            }
        }
        return if (moved == loose.size) Result(Outcome.MOVED, moved) else Result(Outcome.MOVE_FAILED, moved)
    }

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
