package dev.cannoli.scorza.util

import org.apache.commons.compress.archivers.sevenz.SevenZFile
import java.io.File
import java.util.zip.ZipFile

object ArchiveExtractor {

    private val ARCHIVE_EXTENSIONS = setOf("zip", "7z")

    fun isArchive(file: File): Boolean =
        file.extension.lowercase() in ARCHIVE_EXTENSIONS

    fun extract(archive: File, cacheDir: File, desiredBaseName: String): File? {
        val key = "${archive.absolutePath}:${archive.lastModified()}:$desiredBaseName"
        val cacheSubDir = File(cacheDir, "rom_cache/${key.hashCode().toUInt()}")

        val markerFile = File(cacheSubDir, ".extracted")
        if (markerFile.exists() && markerFile.readText() == key) {
            val cached = cacheSubDir.listFiles()?.firstOrNull { it.name != ".extracted" }
            if (cached != null) return cached
        }

        cacheSubDir.deleteRecursively()
        cacheSubDir.mkdirs()

        val extracted = when (archive.extension.lowercase()) {
            "zip" -> extractZip(archive, cacheSubDir, desiredBaseName)
            "7z" -> extract7z(archive, cacheSubDir, desiredBaseName)
            else -> null
        }

        if (extracted != null) markerFile.writeText(key)
        return extracted
    }

    fun primaryEntryName(archive: File): String? {
        val raw = when (archive.extension.lowercase()) {
            "zip" -> primaryZipEntryName(archive)
            "7z" -> primary7zEntryName(archive)
            else -> null
        } ?: return null
        return File(raw).name
    }

    private fun outputName(desiredBaseName: String, entryName: String): String {
        val ext = File(entryName).extension
        return if (ext.isEmpty()) desiredBaseName else "$desiredBaseName.$ext"
    }

    private fun primaryZipEntryName(archive: File): String? =
        ZipFile(archive).use { zip ->
            zip.entries().asSequence().filter { !it.isDirectory }.maxByOrNull { it.size }?.name
        }

    private fun extractZip(archive: File, outDir: File, desiredBaseName: String): File? {
        val entryName = primaryZipEntryName(archive) ?: return null
        ZipFile(archive).use { zip ->
            val entry = zip.getEntry(entryName) ?: return null
            val outFile = File(outDir, outputName(desiredBaseName, entryName))
            zip.getInputStream(entry).use { input ->
                outFile.outputStream().use { input.copyTo(it) }
            }
            return outFile
        }
    }

    private fun primary7zEntryName(archive: File): String? =
        SevenZFile.builder().setFile(archive).get().use { sz ->
            var name: String? = null
            var size = -1L
            var e = sz.nextEntry
            while (e != null) {
                if (!e.isDirectory && e.size > size) { size = e.size; name = e.name }
                e = sz.nextEntry
            }
            name
        }

    private fun extract7z(archive: File, outDir: File, desiredBaseName: String): File? {
        val largestName = primary7zEntryName(archive) ?: return null
        SevenZFile.builder().setFile(archive).get().use { sz ->
            var entry = sz.nextEntry
            while (entry != null) {
                if (entry.name == largestName) {
                    val outFile = File(outDir, outputName(desiredBaseName, entry.name))
                    outFile.outputStream().use { out ->
                        val buf = ByteArray(8192)
                        var n: Int
                        while (sz.read(buf).also { n = it } > 0) out.write(buf, 0, n)
                    }
                    return outFile
                }
                entry = sz.nextEntry
            }
        }
        return null
    }
}
