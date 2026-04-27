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

    data class RenameResult(val success: Boolean, val error: String? = null)

    fun rename(romFile: File, newBaseName: String, platformTag: String): RenameResult {
        val oldBaseName = romFile.nameWithoutExtension
        val extension = romFile.extension
        val romDir = romFile.parentFile ?: return RenameResult(false, "Cannot resolve ROM directory")

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val backupTagDir = File(backupDir, "$platformTag/${oldBaseName}-$timestamp")

        try {
            backupTagDir.mkdirs()
            romFile.copyTo(File(backupTagDir, romFile.name), overwrite = true)
            findArtFile(platformTag, oldBaseName)?.let { artFile ->
                artFile.copyTo(File(backupTagDir, artFile.name), overwrite = true)
            }
            findMatchingFiles(File(savesDir, platformTag), oldBaseName).forEach { save ->
                save.copyTo(File(backupTagDir, "saves_${save.name}"), overwrite = true)
            }
            findMatchingFiles(File(statesDir, platformTag), oldBaseName).forEach { state ->
                state.copyTo(File(backupTagDir, "states_${state.name}"), overwrite = true)
            }
            val stateSubDir = File(File(statesDir, platformTag), oldBaseName)
            if (stateSubDir.isDirectory) {
                stateSubDir.copyRecursively(File(backupTagDir, "statedir_$oldBaseName"), overwrite = true)
            }
            // Back up anything at the target name that the rename phase would clobber.
            backupTargetCollateral(backupTagDir, romDir, extension, newBaseName, platformTag)
        } catch (e: Exception) {
            backupTagDir.deleteRecursively()
            return RenameResult(false, "Backup failed: ${e.message}")
        }

        try {
            val newRomFile = File(romDir, "$newBaseName.$extension")
            if (!romFile.renameTo(newRomFile)) {
                throw Exception("Failed to rename ROM file")
            }
            findArtFile(platformTag, oldBaseName)?.let { artFile ->
                val newArtFile = File(artFile.parentFile, "$newBaseName.${artFile.extension}")
                artFile.renameTo(newArtFile)
            }
            findMatchingFiles(File(savesDir, platformTag), oldBaseName).forEach { save ->
                val newName = save.name.replaceFirst(oldBaseName, newBaseName)
                save.renameTo(File(save.parentFile, newName))
            }
            findMatchingFiles(File(statesDir, platformTag), oldBaseName).forEach { state ->
                val newName = state.name.replaceFirst(oldBaseName, newBaseName)
                state.renameTo(File(state.parentFile, newName))
            }
            val stateSubDir = File(File(statesDir, platformTag), oldBaseName)
            if (stateSubDir.isDirectory) {
                stateSubDir.renameTo(File(File(statesDir, platformTag), newBaseName))
            }
            updateMapFile(romDir, romFile.name, "$newBaseName.$extension")
        } catch (e: Exception) {
            try {
                rollback(backupTagDir, romFile, platformTag, oldBaseName)
            } catch (_: Exception) { }
            return RenameResult(false, "Rename failed: ${e.message}")
        }

        return RenameResult(true)
    }

    private fun backupTargetCollateral(
        rootBackupDir: File,
        romDir: File,
        romExt: String,
        newBaseName: String,
        tag: String
    ) {
        val targetRom = File(romDir, "$newBaseName.$romExt")
        val targetArt = findArtFile(tag, newBaseName)
        val targetSaves = findMatchingFiles(File(savesDir, tag), newBaseName)
        val targetStates = findMatchingFiles(File(statesDir, tag), newBaseName)
        val targetStateSub = File(File(statesDir, tag), newBaseName)

        val anyTarget = targetRom.exists() || targetArt != null ||
            targetSaves.isNotEmpty() || targetStates.isNotEmpty() ||
            targetStateSub.isDirectory
        if (!anyTarget) return

        val targetBackup = File(rootBackupDir, "target")
        targetBackup.mkdirs()
        if (targetRom.exists()) {
            targetRom.copyTo(File(targetBackup, targetRom.name), overwrite = true)
        }
        targetArt?.let { artFile ->
            artFile.copyTo(File(targetBackup, artFile.name), overwrite = true)
        }
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

    private fun rollback(backupDir: File, originalRom: File, tag: String, oldBaseName: String) {
        val romDir = originalRom.parentFile ?: return
        backupDir.listFiles()?.forEach { backup ->
            // The "target" subdir holds files that lived at the new name before
            // the rename clobbered them. Rollback only restores source-side state,
            // so leave target-side captures alone.
            if (backup.isDirectory && backup.name == "target") return@forEach
            when {
                backup.name.startsWith("saves_") -> {
                    val origName = backup.name.removePrefix("saves_")
                    backup.copyTo(File(File(savesDir, tag), origName), overwrite = true)
                }
                backup.name.startsWith("statedir_") -> {
                    val origName = backup.name.removePrefix("statedir_")
                    val targetDir = File(File(statesDir, tag), origName)
                    targetDir.deleteRecursively()
                    backup.copyRecursively(targetDir, overwrite = true)
                }
                backup.name.startsWith("states_") -> {
                    val origName = backup.name.removePrefix("states_")
                    backup.copyTo(File(File(statesDir, tag), origName), overwrite = true)
                }
                backup.name == originalRom.name -> {
                    backup.copyTo(File(romDir, backup.name), overwrite = true)
                }
                else -> {
                    val artTagDir = File(artDir, tag)
                    artTagDir.mkdirs()
                    backup.copyTo(File(artTagDir, backup.name), overwrite = true)
                }
            }
        }
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
            it.nameWithoutExtension == baseName || it.nameWithoutExtension.startsWith("$baseName.")
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
