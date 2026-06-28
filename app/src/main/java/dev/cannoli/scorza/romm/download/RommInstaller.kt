package dev.cannoli.scorza.romm.download

import dev.cannoli.scorza.romm.RommGame
import java.io.File
import java.util.zip.ZipInputStream

data class InstallResult(val linkRelativePath: String, val artBaseName: String)

class RommInstaller {

    fun isMultiPart(game: RommGame): Boolean = game.files.size > 1

    /** Installs [temp] (a downloaded raw file or zip) for [game] under [romDir]/[tag]. Deletes [temp]. */
    fun install(game: RommGame, tag: String, temp: File, romDir: File): InstallResult {
        val tagDir = File(romDir, tag)
        return if (isMultiPart(game)) installMultiPart(game, tag, tagDir, temp)
        else installSingle(game, tag, tagDir, temp)
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

    private fun installMultiPart(game: RommGame, tag: String, tagDir: File, temp: File): InstallResult {
        val folderName = sanitize(game.name)
        val staging = File(tagDir, ".$folderName.tmp")
        if (staging.exists()) staging.deleteRecursively()
        staging.mkdirs()
        var m3u: String? = null
        try {
            ZipInputStream(temp.inputStream()).use { zin ->
                var entry = zin.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val name = File(entry.name).name
                        File(staging, name).outputStream().use { zin.copyTo(it) }
                        if (name.endsWith(".m3u", ignoreCase = true)) m3u = name
                    }
                    entry = zin.nextEntry
                }
            }
        } catch (e: Throwable) {
            staging.deleteRecursively()
            throw e
        }
        temp.delete()
        val dest = File(tagDir, folderName)
        if (dest.exists()) dest.deleteRecursively()
        if (!staging.renameTo(dest)) {
            staging.copyRecursively(dest, overwrite = true)
            staging.deleteRecursively()
        }
        val linkRel = if (m3u != null) "$tag/$folderName/$m3u" else "$tag/$folderName"
        return InstallResult(linkRel, folderName)
    }

    private fun sanitize(name: String): String = name.replace(Regex("[/\\\\:*?\"<>|]"), "_").trim()
}
