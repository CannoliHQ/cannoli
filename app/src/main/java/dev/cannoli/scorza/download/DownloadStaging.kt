package dev.cannoli.scorza.download

import dev.cannoli.scorza.util.RommLog
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Where a download is written before it lands, so no partial file ever sits beside a real one. A
 * download takes a uniquely named file (or directory) here, writes it, then commits it onto its
 * destination or discards it.
 */
class DownloadStaging(private val folder: () -> File) {

    fun file(): File = File(folder().apply { mkdirs() }, "${UUID.randomUUID()}.part")

    fun dir(): File = File(folder().apply { mkdirs() }, "${UUID.randomUUID()}.parts").apply { mkdirs() }

    /** Moves [staged] onto [dest], replacing what is there. Nothing stays staged either way. */
    fun commit(staged: File, dest: File, allowCopy: Boolean = true) {
        try {
            dest.parentFile?.mkdirs()
            if (staged.renameTo(dest)) return
            // A directory cannot be renamed over a non-empty one, and on the card MediaProvider
            // refuses a rename onto a path it already has a row for.
            if (dest.exists()) deleteTree(dest)
            if (staged.renameTo(dest)) return
            // A copy interrupted partway leaves a truncated file at dest, which a core loader
            // would still open, so a caller that needs dest whole refuses it.
            if (!allowCopy) throw java.io.IOException("could not move ${staged.name} onto ${dest.name}")
            // Still refused: the destination is on another volume, such as a rom folder set
            // outside the Cannoli root.
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
