package dev.cannoli.scorza.util

import dev.cannoli.scorza.config.CannoliPaths
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AtomicRename(private val cannoliRoot: File) {

    private val paths = CannoliPaths(cannoliRoot)
    private val backupDir get() = paths.backupDir
    private val savesDir get() = paths.savesDir
    private val statesDir get() = paths.saveStatesDir
    private val artDir get() = paths.artDir

    enum class RenameError { CANNOT_RESOLVE_DIR, ALREADY_EXISTS, BACKUP_FAILED, RENAME_FAILED, RELOCATE_FAILED }

    data class RenameResult(val success: Boolean, val error: RenameError? = null)

    // Carries a structured error through the throw/catch so the raw exception text can be
    // logged (not surfaced) while the UI maps the code to a localized message.
    private class RenameFailure(val error: RenameError) : Exception()

    fun rename(romFile: File, newBaseName: String, platformTag: String): RenameResult {
        val oldBaseName = romFile.nameWithoutExtension
        val extension = romFile.extension
        val romDir = romFile.parentFile ?: return RenameResult(false, RenameError.CANNOT_RESOLVE_DIR)

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val backupTagDir = File(backupDir, "$platformTag/${oldBaseName}-$timestamp")

        // Only save data is backed up: the ROM and art are renamed in place (reversible by
        // renaming back), so copying them would just churn gigabytes of SD I/O for nothing.
        try {
            backupTagDir.mkdirs()
            backupSaveData(backupTagDir, platformTag, oldBaseName)
            backupTargetCollateral(backupTagDir, newBaseName, platformTag)
            if (backupTagDir.list()?.isEmpty() == true) backupTagDir.delete()
        } catch (e: Exception) {
            backupTagDir.deleteRecursively()
            ErrorLog.write("rename backup failed: ${e.message}")
            return RenameResult(false, RenameError.BACKUP_FAILED)
        }

        val newRomFile = File(romDir, "$newBaseName.$extension")
        var romMoved = false
        var artMoved: Pair<File, File>? = null
        try {
            if (newRomFile.exists() && newRomFile != romFile) {
                throw RenameFailure(RenameError.ALREADY_EXISTS)
            }
            if (!romFile.renameTo(newRomFile)) {
                throw RenameFailure(RenameError.RENAME_FAILED)
            }
            romMoved = true
            findArtFile(platformTag, oldBaseName)?.let { artFile ->
                val newArtFile = File(artFile.parentFile, "$newBaseName.${artFile.extension}")
                if (artFile.renameTo(newArtFile)) artMoved = artFile to newArtFile
            }
            moveSaveData(platformTag, oldBaseName, newBaseName)
            updateMapFile(romDir, romFile.name, "$newBaseName.$extension")
        } catch (e: Exception) {
            try {
                rollback(backupTagDir, platformTag, romMoved, romFile, newRomFile, artMoved)
            } catch (_: Exception) { }
            if (e !is RenameFailure) ErrorLog.write("rename failed: ${e.message}")
            return RenameResult(false, (e as? RenameFailure)?.error ?: RenameError.RENAME_FAILED)
        }

        return RenameResult(true)
    }

    // Reconciles a game's save data to a new base name without touching the ROM file or
    // art (used to heal saves written under the wrong name). Assumes the target base name
    // is unoccupied (the caller skips when destination save data already exists); rollback
    // clears any newBase artifacts on failure.
    fun relocateSaveData(platformTag: String, oldBaseName: String, newBaseName: String): RenameResult {
        if (oldBaseName == newBaseName) return RenameResult(true)
        if (!hasSaveData(platformTag, oldBaseName)) return RenameResult(true)

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val backupTagDir = File(backupDir, "$platformTag/relocate-$oldBaseName-$timestamp")
        try {
            backupTagDir.mkdirs()
            backupSaveData(backupTagDir, platformTag, oldBaseName)
        } catch (e: Exception) {
            backupTagDir.deleteRecursively()
            ErrorLog.write("relocate backup failed: ${e.message}")
            return RenameResult(false, RenameError.BACKUP_FAILED)
        }

        try {
            moveSaveData(platformTag, oldBaseName, newBaseName)
        } catch (e: Exception) {
            try {
                clearSaveData(platformTag, newBaseName)
                restoreSaveData(backupTagDir, platformTag)
            } catch (_: Exception) { }
            ErrorLog.write("relocate failed: ${e.message}")
            return RenameResult(false, RenameError.RELOCATE_FAILED)
        }

        return RenameResult(true)
    }

    private fun hasSaveData(tag: String, baseName: String): Boolean {
        val statesTagDir = File(statesDir, tag)
        return File(statesTagDir, baseName).isDirectory ||
            findMatchingFiles(File(savesDir, tag), baseName).isNotEmpty() ||
            findMatchingFiles(statesTagDir, baseName).isNotEmpty()
    }

    private fun backupSaveData(backupTagDir: File, tag: String, baseName: String) {
        findMatchingFiles(File(savesDir, tag), baseName).forEach { save ->
            save.copyTo(File(backupTagDir, "saves_${save.name}"), overwrite = true)
        }
        findMatchingFiles(File(statesDir, tag), baseName).forEach { state ->
            state.copyTo(File(backupTagDir, "states_${state.name}"), overwrite = true)
        }
        val stateSubDir = File(File(statesDir, tag), baseName)
        if (stateSubDir.isDirectory) {
            stateSubDir.copyRecursively(File(backupTagDir, "statedir_$baseName"), overwrite = true)
        }
    }

    private fun moveSaveData(tag: String, oldBaseName: String, newBaseName: String) {
        val savesTagDir = File(savesDir, tag)
        val statesTagDir = File(statesDir, tag)
        moveMatching(savesTagDir, oldBaseName, newBaseName)
        moveMatching(statesTagDir, oldBaseName, newBaseName)
        val stateSubDir = File(statesTagDir, oldBaseName)
        if (stateSubDir.isDirectory) {
            val newStateDir = File(statesTagDir, newBaseName)
            newStateDir.parentFile?.mkdirs()
            if (newStateDir.isDirectory && newStateDir.list()?.isEmpty() == true) newStateDir.delete()
            if (!stateSubDir.renameTo(newStateDir)) throw Exception("Failed to move state dir")
            newStateDir.listFiles()?.forEach { f ->
                if (f.name.startsWith(oldBaseName)) {
                    val renamed = newBaseName + f.name.substring(oldBaseName.length)
                    if (!f.renameTo(File(newStateDir, renamed))) throw Exception("Failed to rename ${f.name}")
                }
            }
        }
    }

    private fun moveMatching(dir: File, oldBaseName: String, newBaseName: String) {
        findMatchingFiles(dir, oldBaseName).forEach { file ->
            val renamed = newBaseName + file.name.substring(oldBaseName.length)
            if (!file.renameTo(File(dir, renamed))) throw Exception("Failed to move ${file.name}")
        }
    }

    private fun clearSaveData(tag: String, baseName: String) {
        File(File(statesDir, tag), baseName).deleteRecursively()
        findMatchingFiles(File(statesDir, tag), baseName).forEach { it.delete() }
        findMatchingFiles(File(savesDir, tag), baseName).forEach { it.delete() }
    }

    private fun restoreSaveData(backupTagDir: File, tag: String) {
        backupTagDir.listFiles()?.forEach { b ->
            when {
                b.name.startsWith("saves_") ->
                    b.copyTo(File(File(savesDir, tag), b.name.removePrefix("saves_")), overwrite = true)
                b.name.startsWith("states_") ->
                    b.copyTo(File(File(statesDir, tag), b.name.removePrefix("states_")), overwrite = true)
                b.name.startsWith("statedir_") -> {
                    val dest = File(File(statesDir, tag), b.name.removePrefix("statedir_"))
                    dest.deleteRecursively()
                    b.copyRecursively(dest, overwrite = true)
                }
            }
        }
    }

    private fun backupTargetCollateral(rootBackupDir: File, newBaseName: String, tag: String) {
        val targetSaves = findMatchingFiles(File(savesDir, tag), newBaseName)
        val targetStates = findMatchingFiles(File(statesDir, tag), newBaseName)
        val targetStateSub = File(File(statesDir, tag), newBaseName)

        val anyTarget = targetSaves.isNotEmpty() || targetStates.isNotEmpty() ||
            targetStateSub.isDirectory
        if (!anyTarget) return

        val targetBackup = File(rootBackupDir, "target")
        targetBackup.mkdirs()
        targetSaves.forEach { save ->
            save.copyTo(File(targetBackup, "saves_${save.name}"), overwrite = true)
        }
        targetStates.forEach { state ->
            state.copyTo(File(targetBackup, "states_${state.name}"), overwrite = true)
        }
        if (targetStateSub.isDirectory) {
            targetStateSub.copyRecursively(File(targetBackup, "statedir_$newBaseName"), overwrite = true)
        }
    }

    private fun rollback(
        backupTagDir: File,
        tag: String,
        romMoved: Boolean,
        originalRom: File,
        newRomFile: File,
        artMoved: Pair<File, File>?,
    ) {
        if (romMoved && !originalRom.exists()) newRomFile.renameTo(originalRom)
        artMoved?.let { (oldArt, newArt) ->
            if (!oldArt.exists()) newArt.renameTo(oldArt)
        }
        restoreSaveData(backupTagDir, tag)
    }

    private fun findArtFile(tag: String, baseName: String): File? {
        val artTagDir = File(artDir, tag)
        if (!artTagDir.exists()) return null
        val extensions = listOf("png", "jpg", "jpeg", "PNG", "JPG", "JPEG")
        for (ext in extensions) {
            val candidate = File(artTagDir, "$baseName.$ext")
            if (candidate.exists()) return candidate
        }
        return null
    }

    private fun findMatchingFiles(dir: File, baseName: String): List<File> {
        if (!dir.exists()) return emptyList()
        return dir.listFiles()?.filter {
            it.isFile && (it.nameWithoutExtension == baseName || it.nameWithoutExtension.startsWith("$baseName."))
        } ?: emptyList()
    }

    private fun updateMapFile(romDir: File, oldFileName: String, newFileName: String) {
        val mapFile = File(romDir, "map.txt")
        if (!mapFile.exists()) return
        try {
            val lines = mapFile.readLines()
            val updated = lines.map { line ->
                if (line.startsWith("$oldFileName\t")) "$newFileName\t${line.substringAfter('\t')}"
                else line
            }
            if (updated != lines) mapFile.writeText(updated.joinToString("\n") + "\n")
        } catch (_: java.io.IOException) { }
    }

}
