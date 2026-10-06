package dev.cannoli.scorza.util

import dev.cannoli.core.RomKey
import dev.cannoli.scorza.config.CannoliPaths
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AtomicRename(private val cannoliRoot: () -> File, private val walker: RomDirectoryWalker, private val artwork: ArtworkLookup) {

    constructor(cannoliRoot: File, walker: RomDirectoryWalker, artwork: ArtworkLookup) :
        this({ cannoliRoot }, walker, artwork)

    private val paths get() = CannoliPaths(cannoliRoot())
    private val backupDir get() = paths.backupDir
    private val savesDir get() = paths.savesDir
    private val statesDir get() = paths.saveStatesDir
    private val artDir get() = paths.artDir

    enum class RenameError { CANNOT_RESOLVE_DIR, ALREADY_EXISTS, BACKUP_FAILED, RENAME_FAILED, RELOCATE_FAILED }

    data class RenameResult(val success: Boolean, val error: RenameError? = null, val newPrimary: File? = null)

    private class RenameFailure(val error: RenameError) : Exception()

    fun rename(romFile: File, newBaseName: String, platformTag: String): RenameResult {
        val oldBaseName = romFile.nameWithoutExtension
        val romDir = romFile.parentFile ?: return RenameResult(false, RenameError.CANNOT_RESOLVE_DIR)
        val gameDirBefore = walker.gameDirectory(romFile)

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val backupTagDir = File(backupDir, "$platformTag/${oldBaseName}-$timestamp")

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

        var romMoved = false
        var newPrimary: File = romFile
        var artMoved: Pair<File, File>? = null
        try {
            val moved = walker.renameGame(romFile, newBaseName)
            when (moved.outcome) {
                RomDirectoryWalker.RenameOutcome.NAME_TAKEN -> throw RenameFailure(RenameError.ALREADY_EXISTS)
                RomDirectoryWalker.RenameOutcome.FAILED -> throw RenameFailure(RenameError.RENAME_FAILED)
                RomDirectoryWalker.RenameOutcome.RENAMED -> Unit
            }
            newPrimary = moved.newPrimary ?: throw RenameFailure(RenameError.RENAME_FAILED)
            romMoved = true
            findArtFile(platformTag, oldBaseName)?.let { artFile ->
                val newArtFile = File(artFile.parentFile, "$newBaseName.${artFile.extension}")
                if (artFile.renameTo(newArtFile)) artMoved = artFile to newArtFile
            }
            moveSaveData(platformTag, oldBaseName, newBaseName)
            val oldContentKey = RomKey.baseName(romFile)
            val newContentKey = RomKey.baseName(newPrimary)
            if (oldContentKey != newContentKey) {
                relocatePerGameContent(backupTagDir, platformTag, oldContentKey, newContentKey)
            }
            if (gameDirBefore == null) {
                updateMapFile(romDir, romFile.name, newPrimary.name)
            }
        } catch (e: Exception) {
            try {
                rollback(backupTagDir, platformTag, romMoved, oldBaseName, newPrimary, artMoved)
            } catch (_: Exception) { }
            artwork.invalidate(platformTag)
            if (e !is RenameFailure) ErrorLog.write("rename failed: ${e.message}")
            return RenameResult(false, (e as? RenameFailure)?.error ?: RenameError.RENAME_FAILED)
        }

        artwork.invalidate(platformTag)
        return RenameResult(true, newPrimary = newPrimary)
    }

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
            File(File(savesDir, tag), baseName).isDirectory ||
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
        val saveSubDir = File(File(savesDir, tag), baseName)
        if (saveSubDir.isDirectory) {
            saveSubDir.copyRecursively(File(backupTagDir, "savedir_$baseName"), overwrite = true)
        }
    }

    private fun moveSaveData(tag: String, oldBaseName: String, newBaseName: String) {
        val savesTagDir = File(savesDir, tag)
        val statesTagDir = File(statesDir, tag)
        moveSaveDir(savesTagDir, oldBaseName, newBaseName)
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

    private fun moveSaveDir(savesTagDir: File, oldBaseName: String, newBaseName: String) {
        val from = File(savesTagDir, oldBaseName)
        if (!from.isDirectory) return
        val to = File(savesTagDir, newBaseName)
        if (to.isDirectory && to.list()?.isEmpty() == true) to.delete()
        if (!from.renameTo(to)) throw Exception("Failed to move save dir")
        moveMatching(to, oldBaseName, newBaseName)
    }

    private fun moveMatching(dir: File, oldBaseName: String, newBaseName: String) {
        findMatchingFiles(dir, oldBaseName).forEach { file ->
            val renamed = newBaseName + file.name.substring(oldBaseName.length)
            if (!file.renameTo(File(dir, renamed))) throw Exception("Failed to move ${file.name}")
        }
    }

    private fun clearSaveData(tag: String, baseName: String) {
        File(File(statesDir, tag), baseName).deleteRecursively()
        File(File(savesDir, tag), baseName).deleteRecursively()
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
                b.name.startsWith("savedir_") -> {
                    val dest = File(File(savesDir, tag), b.name.removePrefix("savedir_"))
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
        val targetSaveSub = File(File(savesDir, tag), newBaseName)

        val anyTarget = targetSaves.isNotEmpty() || targetStates.isNotEmpty() ||
            targetStateSub.isDirectory || targetSaveSub.isDirectory
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
        if (targetSaveSub.isDirectory) {
            targetSaveSub.copyRecursively(File(targetBackup, "savedir_$newBaseName"), overwrite = true)
        }
    }

    private fun rollback(
        backupTagDir: File,
        tag: String,
        romMoved: Boolean,
        oldBaseName: String,
        newPrimary: File,
        artMoved: Pair<File, File>?,
    ) {
        if (romMoved) walker.renameGame(newPrimary, oldBaseName)
        artMoved?.let { (oldArt, newArt) ->
            if (!oldArt.exists()) newArt.renameTo(oldArt)
        }
        restoreSaveData(backupTagDir, tag)
        restorePerGameContent(backupTagDir, tag)
    }

    private fun relocatePerGameContent(backupTagDir: File, tag: String, oldKey: String, newKey: String) {
        val targetCheats = paths.cheatDir(tag, newKey)
        val targetGuides = paths.guideDir(tag, newKey)
        val targetCfg = paths.gameOverrideDir(tag, newKey)
        if (targetCheats.isDirectory || targetGuides.isDirectory || targetCfg.isDirectory) {
            throw RenameFailure(RenameError.ALREADY_EXISTS)
        }

        backupPerGameContent(backupTagDir, tag, oldKey)

        val moved = mutableListOf<Pair<File, File>>()
        try {
            val cheatsSrc = paths.cheatDir(tag, oldKey)
            if (cheatsSrc.isDirectory) {
                targetCheats.parentFile?.mkdirs()
                if (!cheatsSrc.renameTo(targetCheats)) throw Exception("Failed to move cheats dir")
                moved.add(cheatsSrc to targetCheats)
            }
            val guidesSrc = paths.guideDir(tag, oldKey)
            if (guidesSrc.isDirectory) {
                targetGuides.parentFile?.mkdirs()
                if (!guidesSrc.renameTo(targetGuides)) throw Exception("Failed to move guides dir")
                moved.add(guidesSrc to targetGuides)
            }
            val cfgSrc = paths.gameOverrideDir(tag, oldKey)
            if (cfgSrc.isDirectory) {
                targetCfg.parentFile?.mkdirs()
                if (!cfgSrc.renameTo(targetCfg)) throw Exception("Failed to move game override dir")
                moved.add(cfgSrc to targetCfg)
            }
        } catch (e: Exception) {
            moved.asReversed().forEach { (from, to) -> to.renameTo(from) }
            throw e
        }
    }

    private fun backupPerGameContent(backupTagDir: File, tag: String, key: String) {
        val cheats = paths.cheatDir(tag, key)
        val guides = paths.guideDir(tag, key)
        val cfg = paths.gameOverrideDir(tag, key)
        if (!cheats.isDirectory && !guides.isDirectory && !cfg.isDirectory) return

        backupTagDir.mkdirs()
        if (cheats.isDirectory) cheats.copyRecursively(File(backupTagDir, "cheatsdir_$key"), overwrite = true)
        if (guides.isDirectory) guides.copyRecursively(File(backupTagDir, "guidesdir_$key"), overwrite = true)
        if (cfg.isDirectory) cfg.copyRecursively(File(backupTagDir, "cfgdir_$key"), overwrite = true)
    }

    private fun restorePerGameContent(backupTagDir: File, tag: String) {
        backupTagDir.listFiles()?.forEach { b ->
            when {
                b.name.startsWith("cheatsdir_") -> {
                    val dest = paths.cheatDir(tag, b.name.removePrefix("cheatsdir_"))
                    dest.deleteRecursively()
                    b.copyRecursively(dest, overwrite = true)
                }
                b.name.startsWith("guidesdir_") -> {
                    val dest = paths.guideDir(tag, b.name.removePrefix("guidesdir_"))
                    dest.deleteRecursively()
                    b.copyRecursively(dest, overwrite = true)
                }
                b.name.startsWith("cfgdir_") -> {
                    val dest = paths.gameOverrideDir(tag, b.name.removePrefix("cfgdir_"))
                    dest.deleteRecursively()
                    b.copyRecursively(dest, overwrite = true)
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
