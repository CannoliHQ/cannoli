package dev.cannoli.scorza.romm.sync

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

data class LocalSave(
    val files: List<File>,
    val isBundle: Boolean,
    val sizeBytes: Long,
    val modifiedMillis: Long,
    val contentHash: String,
    val uploadFileName: String,
)

class LocalSaveResolver(private val cannoliRoot: File) {

    private fun savesDir(tag: String) = File(File(cannoliRoot, "Saves"), tag)

    private fun matchingFiles(tag: String, base: String): List<File> {
        val dir = savesDir(tag)
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles().orEmpty()
            .filter { it.isFile && (it.nameWithoutExtension == base || it.name.startsWith("$base.")) }
            .sortedBy { it.name }
    }

    fun resolve(tag: String, base: String): LocalSave? {
        val files = matchingFiles(tag, base)
        if (files.isEmpty()) return null
        val isBundle = files.size > 1
        val hash = if (isBundle) {
            SaveHasher.hashBundle(files.associateBy { it.name })
        } else {
            SaveHasher.hashFile(files.single())
        }
        return LocalSave(
            files = files,
            isBundle = isBundle,
            sizeBytes = files.sumOf { it.length() },
            modifiedMillis = files.maxOf { it.lastModified() },
            contentHash = hash,
            uploadFileName = if (isBundle) "$base.zip" else "$base.srm",
        )
    }

    fun bundleToZip(tag: String, base: String, dest: File): File {
        val files = matchingFiles(tag, base)
        ZipOutputStream(dest.outputStream()).use { zos ->
            for (f in files.sortedBy { it.name }) {
                zos.putNextEntry(ZipEntry(f.name))
                f.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }
        }
        return dest
    }

    fun applyDownload(tag: String, base: String, downloaded: File) {
        val dir = savesDir(tag).apply { mkdirs() }
        if (isZip(downloaded)) {
            ZipFile(downloaded).use { zf ->
                for (entry in zf.entries()) {
                    if (entry.isDirectory) continue
                    val out = File(dir, File(entry.name).name)
                    zf.getInputStream(entry).use { ins -> out.outputStream().use { ins.copyTo(it) } }
                }
            }
        } else {
            downloaded.copyTo(File(dir, "$base.srm"), overwrite = true)
        }
    }

    private fun isZip(file: File): Boolean = file.inputStream().use { ins ->
        val sig = ByteArray(4)
        ins.read(sig) == 4 && sig[0] == 0x50.toByte() && sig[1] == 0x4B.toByte()
    }
}
