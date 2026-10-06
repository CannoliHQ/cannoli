package dev.cannoli.scorza.download

import dev.cannoli.scorza.util.RommLog
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class DownloadStaging(private val folder: () -> File) {

    fun file(): File = File(folder().apply { mkdirs() }, "${UUID.randomUUID()}.part")

    fun dir(): File = File(folder().apply { mkdirs() }, "${UUID.randomUUID()}.parts").apply { mkdirs() }

    fun commit(staged: File, dest: File, allowCopy: Boolean = true) {
        try {
            dest.parentFile?.mkdirs()
            if (staged.renameTo(dest)) return
            if (dest.exists()) deleteTree(dest)
            if (staged.renameTo(dest)) return
            if (!allowCopy) throw java.io.IOException("could not move ${staged.name} onto ${dest.name}")
            if (staged.isDirectory) staged.copyRecursively(dest, overwrite = true)
            else staged.copyTo(dest, overwrite = true)
        } finally {
            discard(staged)
        }
    }

    fun discard(staged: File) {
        deleteTree(staged)
    }

    companion object {
        private const val LEGACY = "Config/Cache/RommDownloads"
        private val swept = AtomicBoolean(false)

        fun cardFolder(cannoliRoot: File) = File(cannoliRoot, "Config/Cache/Downloads")

        // The queue lives in memory and nothing resumes from a staged file, so at process start
        // everything staged is left over from a process that died mid-download. Once per process:
        // boot runs again when the activity is recreated, and downloads may be running by then.
        fun sweepOnce(cannoliRoot: File) {
            if (swept.compareAndSet(false, true)) sweep(cannoliRoot)
        }

        fun sweep(cannoliRoot: File, log: (String) -> Unit = RommLog::write) {
            cardFolder(cannoliRoot).listFiles().orEmpty().forEach { remove(it, log) }
            val legacy = File(cannoliRoot, LEGACY)
            if (legacy.isDirectory) {
                legacy.listFiles().orEmpty().forEach { remove(it, log) }
                legacy.delete()
            }
        }

        private fun remove(entry: File, log: (String) -> Unit) {
            val size = sizeOf(entry)
            if (deleteTree(entry)) log("swept stale download ${entry.name} ($size bytes)")
            else log("ERROR sweep could not remove ${entry.name}")
        }

        private fun isLink(f: File) = Files.isSymbolicLink(f.toPath())

        private fun sizeOf(f: File): Long = when {
            isLink(f) -> 0L
            f.isDirectory -> f.listFiles()?.sumOf { sizeOf(it) } ?: 0L
            else -> f.length()
        }

        private fun deleteTree(f: File): Boolean {
            if (f.isDirectory && !isLink(f)) f.listFiles()?.forEach { deleteTree(it) }
            return f.delete()
        }
    }
}
