package dev.cannoli.scorza.launcher

import android.content.res.AssetManager
import dev.cannoli.scorza.util.ErrorLog
import java.io.File
import java.io.InputStream
import java.util.Locale
import java.util.zip.ZipInputStream

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

    @Volatile private var cached: List<Entry>? = null

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

    private fun normalise(coreId: String): String =
        if (coreId.endsWith("_libretro")) coreId else "${coreId}_libretro"

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

    fun remoteSetsPresent(assets: AssetManager, coreId: String, tag: String, biosDir: File): Boolean =
        remoteFor(assets, coreId)
            .filter { it.tag == tag.uppercase(Locale.ROOT) }
            .all { it.foldersPresent(biosDir) }

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
