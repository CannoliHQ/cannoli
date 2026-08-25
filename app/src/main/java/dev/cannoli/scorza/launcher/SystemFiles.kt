package dev.cannoli.scorza.launcher

import android.content.res.AssetManager
import dev.cannoli.scorza.util.ErrorLog
import java.io.File
import java.io.InputStream
import java.util.Locale
import java.util.zip.ZipInputStream

/**
 * System files a core needs beyond its BIOS, described by `system_files.txt`.
 *
 * Bundled sets ride in `assets/system` and extract on first launch of the platform, so a set is
 * only paid for by someone who plays that platform. Remote sets come from the buildbot when the
 * core is downloaded, which keeps ScummVM's 76 MB and blueMSX's console firmware out of the APK.
 */
object SystemFiles {

    const val BUILDBOT_SYSTEM = "https://buildbot.libretro.com/assets/system"

    private const val MANIFEST = "system_files.txt"
    private const val ASSET_DIR = "system"
    private const val MARKER = ".cannoli_system"

    data class Entry(
        val core: String,
        val tag: String,
        val bundled: Boolean,
        val folders: List<String>,
        val archive: String,
    ) {
        val assetPath: String get() = "$ASSET_DIR/$archive"

        fun foldersPresent(biosDir: File): Boolean = folders.all { File(biosDir, it).isDirectory }
    }

    // Read once. ensureBundled runs on every launch and is a marker check on all but the first,
    // so the manifest must not cost an asset open and a parse each time.
    @Volatile private var cached: List<Entry>? = null

    // A failed or empty read is never cached. The manifest is never legitimately empty, so an
    // empty result means the assets were not readable, and memoizing that would make one bad read
    // permanent for the life of the process.
    fun manifest(assets: AssetManager): List<Entry> =
        cached ?: synchronized(this) {
            cached ?: read(assets).also { if (it.isNotEmpty()) cached = it }
        }

    private fun read(assets: AssetManager): List<Entry> = try {
        assets.open(MANIFEST).bufferedReader().useLines { lines ->
            lines.mapNotNull { parse(it) }.toList()
        }
    } catch (e: Exception) {
        ErrorLog.write("$MANIFEST unreadable: ${e.message}")
        emptyList()
    }

    private fun parse(line: String): Entry? {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("#")) return null
        // Limit 5 so the archive keeps its spaces: "FinalBurn Neo (hiscore).zip" is one field.
        val parts = trimmed.split(Regex("\\s+"), limit = 5)
        if (parts.size < 5) return null
        val bundled = when (parts[2].lowercase(Locale.ROOT)) {
            "bundled" -> true
            "remote" -> false
            else -> return null
        }
        val folders = parts[3].split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (folders.isEmpty()) return null
        return Entry(parts[0], parts[1].uppercase(Locale.ROOT), bundled, folders, parts[4].trim())
    }

    fun remoteFor(assets: AssetManager, coreId: String): List<Entry> =
        manifest(assets).filter { !it.bundled && it.core == normalise(coreId) }

    fun bundledFor(assets: AssetManager, tag: String): List<Entry> =
        manifest(assets).filter { it.bundled && it.tag == tag.uppercase(Locale.ROOT) }

    // Callers name a core either way; the buildbot and the manifest both spell it in full.
    private fun normalise(coreId: String): String =
        if (coreId.endsWith("_libretro")) coreId else "${coreId}_libretro"

    /**
     * Extract the bundled sets for [tag] into [biosDir]. The marker records which archives were laid
     * down as well as the build, so a later release adding a set to a platform that is already
     * marked still extracts it.
     *
     * The folders are checked too, not only the marker. A user who deletes one would otherwise keep
     * a marker claiming this build already delivered it, and the platform would stay broken with
     * the APK still carrying the fix.
     */
    fun ensureBundled(assets: AssetManager, tag: String, biosDir: File, apkStamp: String) {
        if (apkStamp.isEmpty()) return
        val entries = bundledFor(assets, tag)
        if (entries.isEmpty()) return
        val want = (listOf(apkStamp) + entries.map { it.archive }.sorted()).joinToString("\n")
        val marker = File(biosDir, MARKER)
        val marked = marker.exists() && runCatching { marker.readText() }.getOrNull() == want
        if (marked && entries.all { it.foldersPresent(biosDir) }) return
        for (entry in entries) {
            try {
                assets.open(entry.assetPath).use { install(it, biosDir) }
            } catch (e: Exception) {
                ErrorLog.write("system files: ${entry.archive} failed for $tag: ${e.message}")
                return
            }
        }
        runCatching {
            biosDir.mkdirs()
            marker.writeText(want)
        }
    }

    /**
     * Whether the remote sets a core needs on [tag] have arrived. Bundled sets are deliberately not
     * consulted: the APK carries them and [ensureBundled] lays them down at launch, so their
     * absence is never something a download would fix and must not read as a core that is missing.
     */
    fun remoteSetsPresent(assets: AssetManager, coreId: String, tag: String, biosDir: File): Boolean =
        remoteFor(assets, coreId)
            .filter { it.tag == tag.uppercase(Locale.ROOT) }
            .all { it.foldersPresent(biosDir) }

    /**
     * Unpack an archive into [biosDir], keeping the paths it declares: cores look for
     * `PPSSPP/flash0`, `mame2003-plus/cheat.dat` and the like under the system directory.
     * Never deletes, so a file the user put there by hand outlives an extraction that skips it.
     */
    fun install(stream: InputStream, biosDir: File) {
        val root = biosDir.canonicalFile
        root.mkdirs()
        ZipInputStream(stream.buffered()).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                if (entry.isDirectory) {
                    zis.closeEntry()
                    continue
                }
                val dest = File(root, entry.name).canonicalFile
                // A zip may name "../" and escape the system directory; refuse anything that
                // resolves outside it rather than trusting the archive.
                if (!dest.path.startsWith(root.path + File.separator)) {
                    ErrorLog.write("system files: refused escaping entry ${entry.name}")
                    zis.closeEntry()
                    continue
                }
                dest.parentFile?.mkdirs()
                dest.outputStream().use { zis.copyTo(it) }
                zis.closeEntry()
            }
        }
    }
}
