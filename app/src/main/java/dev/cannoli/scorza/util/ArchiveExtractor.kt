package dev.cannoli.scorza.util

import org.apache.commons.compress.archivers.sevenz.SevenZFile
import java.io.File
import java.util.zip.ZipFile

object ArchiveExtractor {

    private val ARCHIVE_EXTENSIONS = setOf("zip", "7z")

    fun isArchive(file: File): Boolean =
        file.extension.lowercase() in ARCHIVE_EXTENSIONS

    fun extract(archive: File, cacheDir: File): File? {
        val key = "${archive.absolutePath}:${archive.lastModified()}"
        val cacheSubDir = File(cacheDir, "rom_cache/${key.hashCode().toUInt()}")

        val markerFile = File(cacheSubDir, ".extracted")
        if (markerFile.exists()) {
            val cached = cacheSubDir.listFiles()?.firstOrNull { it.name != ".extracted" }
            if (cached != null) return cached
        }

        cacheSubDir.deleteRecursively()
        cacheSubDir.mkdirs()

        val extracted = when (archive.extension.lowercase()) {
            "zip" -> extractZip(archive, cacheSubDir)
            "7z" -> extract7z(archive, cacheSubDir)
            else -> null
        }

        if (extracted != null) markerFile.writeText(key)
        return extracted
    }

    private fun extractZip(archive: File, outDir: File): File? {
        ZipFile(archive).use { zip ->
            val entry = zip.entries().asSequence()
                .filter { !it.isDirectory }
                .maxByOrNull { it.size }
                ?: return null
            val outFile = File(outDir, File(entry.name).name)
            zip.getInputStream(entry).use { input ->
                outFile.outputStream().use { input.copyTo(it) }
            }
            return outFile
        }
    }

    private fun extract7z(archive: File, outDir: File): File? {
        SevenZFile.builder().setFile(archive).get().use { sevenZ ->
            var largest: org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry? = null
            for (entry in sevenZ.entries) {
                if (!entry.isDirectory && (largest == null || entry.size > largest.size)) {
                    largest = entry
                }
            }
            if (largest == null) return null
            val outFile = File(outDir, File(largest.name).name)
            // Re-open and stream to the target entry
            SevenZFile.builder().setFile(archive).get().use { sz ->
                var e = sz.nextEntry
                while (e != null) {
                    if (e.name == largest.name) {
                        outFile.outputStream().use { out ->
                            val buf = ByteArray(8192)
                            var n: Int
                            while (sz.read(buf).also { n = it } > 0) {
                                out.write(buf, 0, n)
                            }
                        }
                        return outFile
                    }
                    e = sz.nextEntry
                }
            }
            return null
        }
    }
}
