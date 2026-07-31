package dev.cannoli.scorza.romm.download

import dev.cannoli.scorza.romm.RommGame
import java.io.File

data class InstallResult(val linkRelativePath: String, val artBaseName: String)

class RommInstaller {

    fun isMultiPart(game: RommGame): Boolean = game.files.size > 1

    /** Installs [source] for [game] under [romDir]/[tag]. [source] is a downloaded file for single-part games and a staged directory for multi-part games. Deletes [source]. */
    fun install(game: RommGame, tag: String, source: File, romDir: File): InstallResult {
        val tagDir = File(romDir, tag)
        return if (isMultiPart(game)) installMultiPart(game, tag, tagDir, source)
        else installSingle(game, tag, tagDir, source)
    }

    private fun installSingle(game: RommGame, tag: String, tagDir: File, temp: File): InstallResult {
        tagDir.mkdirs()
        val safeName = File(game.fsName).name
        val dest = File(tagDir, safeName)
        if (!dest.canonicalPath.startsWith(tagDir.canonicalPath)) throw Exception("invalid fsName: path traversal")
        if (dest.exists()) dest.delete()
        if (!temp.renameTo(dest)) { temp.copyTo(dest, overwrite = true); temp.delete() }
        return InstallResult("$tag/$safeName", dest.nameWithoutExtension)
    }

    private fun installMultiPart(game: RommGame, tag: String, tagDir: File, staging: File): InstallResult {
        val folderName = sanitizeFsName(game.name)
        val topLevel = staging.listFiles { f: File -> f.isFile }.orEmpty().sortedBy { it.name.lowercase() }
        val single = topLevel.singleOrNull()
        val launchName = when {
            single != null -> renameToFolderName(single, folderName)
            else -> topLevel.firstOrNull { it.extension.equals("m3u", ignoreCase = true) }?.name
        }
        tagDir.mkdirs()
        val dest = File(tagDir, folderName)
        if (!dest.canonicalPath.startsWith(tagDir.canonicalPath + File.separator)) throw Exception("invalid game name: path traversal")
        if (dest.exists()) dest.deleteRecursively()
        if (!staging.renameTo(dest)) {
            staging.copyRecursively(dest, overwrite = true)
            staging.deleteRecursively()
        }
        val linkRel = if (launchName != null) "$tag/$folderName/$launchName" else "$tag/$folderName"
        return InstallResult(linkRel, folderName)
    }

    private fun renameToFolderName(file: File, folderName: String): String {
        if (file.extension.isEmpty()) return file.name
        val target = File(file.parentFile, "$folderName.${file.extension}")
        if (file.name == target.name) return file.name
        return if (!target.exists() && file.renameTo(target)) target.name else file.name
    }
}
