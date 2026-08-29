package dev.cannoli.scorza.launcher

import dev.cannoli.scorza.config.CannoliPaths
import dev.cannoli.scorza.download.DownloadCancelled
import dev.cannoli.scorza.download.DownloadHandler
import dev.cannoli.scorza.download.DownloadItem
import dev.cannoli.scorza.download.DownloadKind
import dev.cannoli.scorza.settings.SettingsRepository
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * One archive from the libretro shader database, unpacked into Shaders/.
 *
 * Both formats are fetched, because which one a platform can load depends on the video driver it
 * runs and only the menu knows that. RetroArch's own updater offers them as separate downloads;
 * Cannoli takes both and filters at the point of choosing instead, so a user never has to know
 * that glsl means GL and slang means Vulkan.
 */
class ShaderDownloadHandler(
    private val settings: SettingsRepository,
) : DownloadHandler {

    override val kind = DownloadKind.SHADER

    override fun run(
        item: DownloadItem,
        onProgress: (Long, Long) -> Unit,
        isCancelled: () -> Boolean,
    ) {
        val archive = item.payload as? String ?: throw Exception("not a shader archive")
        val dest = CannoliPaths(settings.sdCardRoot).shadersDir
        dest.mkdirs()

        // Three thousand small files onto a FUSE-mounted card is enough sustained I/O to make the
        // launcher stutter while it runs, and nothing is waiting on it. Dropping to background
        // priority lets the scheduler give the UI its time back; the download takes a little longer
        // and no one is watching it.
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)

        val conn = (URL("$BASE_URL$archive.zip").openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 30_000
            instanceFollowRedirects = true
        }
        try {
            if (conn.responseCode !in 200..299) throw Exception("http ${conn.responseCode}")
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: 0L
            var read = 0L
            var written = 0
            ZipInputStream(conn.inputStream.buffered()).use { zip ->
                while (true) {
                    if (isCancelled()) throw DownloadCancelled()
                    val entry = zip.nextEntry ?: break
                    val target = resolve(dest, entry.name)
                    if (target == null || entry.isDirectory) {
                        zip.closeEntry()
                        continue
                    }
                    target.parentFile?.mkdirs()
                    target.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            if (isCancelled()) throw DownloadCancelled()
                            val n = zip.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                        }
                    }
                    zip.closeEntry()
                    written++
                    // A yield every so often rather than every file: the cost is in the syscalls,
                    // and the point is to leave gaps the UI thread can be scheduled into.
                    if (written % YIELD_EVERY == 0) Thread.yield()
                    // Compressed bytes are what the content length measures, and the stream does not
                    // report them, so progress is counted as entries against an estimate instead.
                    read += entry.compressedSize.takeIf { it > 0 } ?: 0L
                    onProgress(read.coerceAtMost(total), total)
                }
            }
            // Built here, at the end of an extraction, because deciding whether a folder leads
            // anywhere means walking the tree, and doing that per render is what made the browser
            // crawl. Rebuilt by each archive so the second one sees what the first left.
            dev.cannoli.core.shader.ShaderIndex.build(dest)
            onProgress(total, total)
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Where an archive entry lands, or null to skip it.
     *
     * Extracted exactly as authored, top-level folder included: presets reference their sources and
     * textures by relative path, so removing a level puts those references outside the tree.
     */
    private fun resolve(dest: File, rawName: String): File? {
        val parts = rawName.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.isEmpty()) return null
        // A traversal entry rewrites a file outside the tree entirely, which is worth refusing even
        // from a source we trust.
        if (parts.any { it == ".." }) return null
        // The archive carries the repository's own furniture: a deploy script, readmes, a spec.
        // None of it is a shader, and every one of them is a file the card has to hold and the
        // browser has to walk past.
        if (parts.last().substringAfterLast('.', "").lowercase() in SKIPPED_EXTENSIONS) return null
        if (parts.firstOrNull().equals(dev.cannoli.core.shader.ShaderCatalog.CUSTOM_DIR, ignoreCase = true)) return null
        if (parts.firstOrNull().equals(RESERVED_BUNDLED, ignoreCase = true)) return null
        return File(dest, parts.joinToString(File.separator))
    }

    companion object {
        private const val BASE_URL = "https://buildbot.libretro.com/assets/frontend/"

        /** Documentation and tooling that ships alongside the shaders. */
        private val SKIPPED_EXTENSIONS = setOf("py", "md", "txt", "sh", "yml", "yaml")
        private const val YIELD_EVERY = 32

        private const val RESERVED_BUNDLED = "crt-cannoli"

        /**
         * Both formats: the driver decides which is offered, not the download. The labels are the
         * shading languages by their own names, so they are not translated: GLSL is an acronym and
         * Slang is what libretro calls its format.
         */
        val ARCHIVES = mapOf("shaders_glsl" to "GLSL", "shaders_slang" to "Slang")

        fun keyFor(archive: String) = "SHADER-$archive"
    }
}
