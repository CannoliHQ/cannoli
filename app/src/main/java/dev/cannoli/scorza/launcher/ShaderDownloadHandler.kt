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
                    if (written % YIELD_EVERY == 0) Thread.yield()
                    read += entry.compressedSize.takeIf { it > 0 } ?: 0L
                    onProgress(read.coerceAtMost(total), total)
                }
            }
            dev.cannoli.core.shader.ShaderIndex.build(dest)
            onProgress(total, total)
        } finally {
            conn.disconnect()
        }
    }

    private fun resolve(dest: File, rawName: String): File? {
        val parts = rawName.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.isEmpty()) return null
        if (parts.any { it == ".." }) return null
        if (parts.last().substringAfterLast('.', "").lowercase() in SKIPPED_EXTENSIONS) return null
        if (parts.firstOrNull().equals(dev.cannoli.core.shader.ShaderCatalog.CUSTOM_DIR, ignoreCase = true)) return null
        if (parts.firstOrNull().equals(RESERVED_BUNDLED, ignoreCase = true)) return null
        return File(dest, parts.joinToString(File.separator))
    }

    companion object {
        private const val BASE_URL = "https://buildbot.libretro.com/assets/frontend/"

        private val SKIPPED_EXTENSIONS = setOf("py", "md", "txt", "sh", "yml", "yaml")
        private const val YIELD_EVERY = 32

        private const val RESERVED_BUNDLED = "crt-cannoli"

        val ARCHIVES = mapOf("shaders_glsl" to "GLSL", "shaders_slang" to "Slang")

        fun keyFor(archive: String) = "SHADER-$archive"
    }
}
