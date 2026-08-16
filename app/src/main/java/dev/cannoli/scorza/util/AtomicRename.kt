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

    // Carries a structured error through the throw/catch so the raw exception text can be
    // logged (not surfaced) while the UI maps the code to a localized message.
    private class RenameFailure(val error: RenameError) : Exception()

    fun rename(romFile: File, newBaseName: String, platformTag: String): RenameResult {
        val oldBaseName = romFile.nameWithoutExtension
        val romDir = romFile.parentFile ?: return RenameResult(false, RenameError.CANNOT_RESOLVE_DIR)
        val gameDirBefore = walker.gameDirectory(romFile)

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
            // Cheats/guides/config are keyed by RomKey.baseName, not the raw file base name used
            // above for saves - that's where the content actually lives.
            val oldContentKey = RomKey.baseName(romFile)
            val newContentKey = RomKey.baseName(newPrimary)
            if (oldContentKey != newContentKey) {
                relocatePerGameContent(backupTagDir, platformTag, oldContentKey, newContentKey)
            }
            // Arcade map.txt applies to loose files; folder games already moved with their parent dir.
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

    // Moves the cheats dir, guides dir, and per-game override cfg from oldKey to newKey. Checks
    // for a newKey collision first and refuses to clobber it, without touching or backing up
    // anything - the caller's catch block rolls the whole rename back in that case, and since
    // nothing here was ever moved there is nothing for that rollback to restore. Only once the
    // collision check passes does it back up the old-key content (so a failed move can be
    // restored) and perform the move.
    private fun relocatePerGameContent(backupTagDir: File, tag: String, oldKey: String, newKey: String) {
        val targetCheats = paths.cheatDir(tag, newKey)
        val targetGuides = paths.guideDir(tag, newKey)
        val targetCfg = paths.gameOverrideCfg(tag, newKey)
        if (targetCheats.isDirectory || targetGuides.isDirectory || targetCfg.isFile) {
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
            val cfgSrc = paths.gameOverrideCfg(tag, oldKey)
            if (cfgSrc.isFile) {
                targetCfg.parentFile?.mkdirs()
                if (!cfgSrc.renameTo(targetCfg)) throw Exception("Failed to move game override cfg")
                moved.add(cfgSrc to targetCfg)
            }
        } catch (e: Exception) {
            // Undo whatever already moved in this call before the outer catch rolls the rest
            // of the rename back too.
            moved.asReversed().forEach { (from, to) -> to.renameTo(from) }
            throw e
        }
    }

    private fun backupPerGameContent(backupTagDir: File, tag: String, key: String) {
        val cheats = paths.cheatDir(tag, key)
        val guides = paths.guideDir(tag, key)
        val cfg = paths.gameOverrideCfg(tag, key)
        if (!cheats.isDirectory && !guides.isDirectory && !cfg.isFile) return

        backupTagDir.mkdirs()
        if (cheats.isDirectory) cheats.copyRecursively(File(backupTagDir, "cheatsdir_$key"), overwrite = true)
        if (guides.isDirectory) guides.copyRecursively(File(backupTagDir, "guidesdir_$key"), overwrite = true)
        if (cfg.isFile) cfg.copyTo(File(backupTagDir, "cfg_$key.cfg"), overwrite = true)
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
                b.name.startsWith("cfg_") -> {
                    val dest = paths.gameOverrideCfg(tag, b.name.removePrefix("cfg_").removeSuffix(".cfg"))
                    dest.parentFile?.mkdirs()
                    b.copyTo(dest, overwrite = true)
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
