package dev.cannoli.scorza.launcher

import android.content.Context
import android.util.Log
import dev.cannoli.scorza.download.DownloadStaging
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

object EmbeddedCoreDownloader {
    private const val TAG = "EmbeddedCoreDownloader"
    private const val BUILDBOT = "https://buildbot.libretro.com/nightly/android/latest"
    private const val INFO_ZIP_URL = "https://buildbot.libretro.com/assets/frontend/info.zip"
    private const val INFO_MARKER = ".cannoli_info_fetched"

    private fun indexUrl(abi: String) = "$BUILDBOT/$abi/.index-extended"

    fun coresDir(context: Context): File = File(context.filesDir, "cores")

    internal fun parseIndex(lines: Sequence<String>): Map<String, String> =
        lines.mapNotNull { line ->
            val parts = line.trim().split(' ').filter { it.isNotEmpty() }
            if (parts.size == 3 && parts[1].length == 8 && parts[1].all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) {
                parts[2] to parts[1].lowercase()
            } else null
        }.toMap()

    fun fetchPublishedCrcs(): Map<String, String> = try {
        val conn = URL(indexUrl(pickAbi())).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.instanceFollowRedirects = true
        try {
            if (conn.responseCode !in 200..299) emptyMap() else {
                conn.inputStream.bufferedReader().useLines { parseIndex(it) }
            }
        } finally {
            conn.disconnect()
        }
    } catch (t: Throwable) {
        Log.w(TAG, "index-extended unavailable, falling back to etags", t)
        emptyMap()
    }

    fun download(
        context: Context,
        coreName: String,
        forceInfoRefresh: Boolean = false,
        conditional: Boolean = false,
        publishedCrcs: Map<String, String> = emptyMap(),
        onBytes: ((read: Long, total: Long) -> Unit)? = null,
    ): CoreDownloadService.Result {
        val abi = pickAbi()
        val coresDir = coresDir(context).apply { mkdirs() }
        val infoDir = File(context.filesDir, "info").apply { mkdirs() }
        val baseName = coreName.removeSuffix("_libretro")
        val soName = "${baseName}_libretro_android.so"
        val soUrl = soUrlFor(coreName)

        return try {
            ensureInfoFiles(infoDir, context.cacheDir, forceInfoRefresh)

            val soFile = File(coresDir, soName)
            if (conditional && soFile.isFile) {
                val published = publishedCrcs["$soName.zip"]
                val installed = DownloadStamps.crcFor(context.filesDir, soUrl)
                if (published != null && published == installed) {
                    Log.i(TAG, "$soName unchanged by CRC, not fetched")
                    return CoreDownloadService.Result("core", coreName, true, null, changed = false)
                }
            }

            val tmp = File.createTempFile("core_", ".zip", context.cacheDir)
            val known = if (conditional) {
                DownloadStamps.etagFor(context.filesDir, soUrl)
                    ?: BundledCoreManifest.etagFor(context.assets, coreName)
            } else null
            try {
                val result = fetch(soUrl, tmp, known, onBytes)
                if (!result.modified) {
                    DownloadStamps.put(
                        context.filesDir, soUrl, result.etag, result.built, crc32Of(soFile),
                    )
                    Log.i(TAG, "$soName already current")
                    return CoreDownloadService.Result("core", coreName, true, null, changed = false)
                }
                extractEntry(tmp, soName, soFile, DownloadStaging { context.cacheDir })
                DownloadStamps.put(
                    context.filesDir, soUrl, result.etag, result.built, crc32Of(soFile),
                )
            } finally {
                tmp.delete()
            }
            Log.i(TAG, "installed $soName")
            CoreDownloadService.Result("core", coreName, true, null, changed = true)
        } catch (t: Throwable) {
            Log.w(TAG, "download failed for $coreName", t)
            CoreDownloadService.Result("core", coreName, false, t.message ?: t.javaClass.simpleName)
        }
    }

    fun refreshInfo(context: Context): CoreDownloadService.Result = try {
        ensureInfoFiles(File(context.filesDir, "info"), context.cacheDir, force = true)
        CoreDownloadService.Result("info", null, true, null)
    } catch (t: Throwable) {
        Log.w(TAG, "info refresh failed", t)
        CoreDownloadService.Result("info", null, false, t.message ?: t.javaClass.simpleName)
    }

    @Synchronized
    private fun ensureInfoFiles(infoDir: File, cacheDir: File, force: Boolean = false) {
        val marker = File(infoDir, INFO_MARKER)
        if (!force && marker.exists()) return

        infoDir.mkdirs()
        val tmp = File.createTempFile("info_", ".zip", cacheDir)
        try {
            val known = DownloadStamps.etagFor(infoDir.parentFile ?: infoDir, INFO_ZIP_URL)
            val result = fetch(INFO_ZIP_URL, tmp, known)
            if (!result.modified) {
                marker.writeText(System.currentTimeMillis().toString())
                return
            }
            extractAllInfo(tmp, infoDir)
            DownloadStamps.put(infoDir.parentFile ?: infoDir, INFO_ZIP_URL, result.etag, result.built)
            marker.writeText(System.currentTimeMillis().toString())
            Log.i(TAG, "info files refreshed into ${infoDir.absolutePath}")
        } finally {
            tmp.delete()
        }
    }

    fun installRemoteSystemFiles(
        context: Context,
        coreName: String,
        conditional: Boolean = false,
        biosFor: (String) -> File,
    ): SystemFilesResult {
        var allLanded = true
        var changed = 0
        for (entry in SystemFiles.remoteFor(context.assets, coreName)) {
            val tmp = File.createTempFile("system_", ".zip", context.cacheDir)
            val url = "${SystemFiles.BUILDBOT_SYSTEM}/${encode(entry.archive)}"
            val dest = biosFor(entry.tag)
            val known = if (conditional && entry.foldersPresent(dest)) {
                DownloadStamps.etagFor(context.filesDir, url)
            } else null
            try {
                val result = fetch(url, tmp, known)
                if (!result.modified) {
                    DownloadStamps.put(context.filesDir, url, result.etag, result.built)
                    Log.i(TAG, "${entry.archive} already current")
                    continue
                }
                tmp.inputStream().use { SystemFiles.install(it, dest) }
                DownloadStamps.put(context.filesDir, url, result.etag, result.built)
                changed++
                Log.i(TAG, "installed ${entry.archive} into ${dest.name}")
            } catch (t: Throwable) {
                Log.w(TAG, "system files ${entry.archive} failed for $coreName", t)
                allLanded = false
            } finally {
                tmp.delete()
            }
        }
        return SystemFilesResult(allLanded, changed)
    }

    data class SystemFilesResult(val ok: Boolean, val changed: Int)

    fun archiveNameFor(coreName: String): String =
        "${coreName.removeSuffix("_libretro")}_libretro_android.so.zip"

    fun soUrlFor(coreName: String): String = "$BUILDBOT/${pickAbi()}/${archiveNameFor(coreName)}"

    internal fun crc32Of(file: File): String? = try {
        val crc = java.util.zip.CRC32()
        file.inputStream().buffered().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                crc.update(buf, 0, n)
            }
        }
        "%08x".format(crc.value)
    } catch (_: Throwable) {
        null
    }

    data class Availability(val stale: Boolean, val bytes: Long)

    fun checkAvailability(coreName: String, knownEtag: String?): Availability = try {
        val conn = (URL(soUrlFor(coreName)).openConnection() as HttpURLConnection).apply {
            requestMethod = "HEAD"
            connectTimeout = 15_000
            readTimeout = 15_000
            instanceFollowRedirects = true
            if (knownEtag != null) setRequestProperty("If-None-Match", knownEtag)
        }
        try {
            when (conn.responseCode) {
                HttpURLConnection.HTTP_NOT_MODIFIED -> Availability(false, 0L)
                in 200..299 -> Availability(true, conn.contentLengthLong.coerceAtLeast(0L))
                else -> Availability(true, 0L)
            }
        } finally {
            conn.disconnect()
        }
    } catch (_: Throwable) {
        Availability(true, 0L)
    }

    private fun encode(name: String): String = name.replace(" ", "%20")

    private fun pickAbi(): String = DeviceAbi.primary()

    data class Fetched(val modified: Boolean, val etag: String?, val built: String = "")

    internal fun fetch(
        url: String,
        out: File,
        etag: String? = null,
        onBytes: ((read: Long, total: Long) -> Unit)? = null,
    ): Fetched {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 60_000
        conn.instanceFollowRedirects = true
        if (etag != null) conn.setRequestProperty("If-None-Match", etag)
        try {
            val code = conn.responseCode
            if (code == HttpURLConnection.HTTP_NOT_MODIFIED) {
                return Fetched(false, etag, DownloadStamps.isoDate(conn.getHeaderField("Last-Modified")))
            }
            if (code !in 200..299) throw RuntimeException("HTTP $code for $url")
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                out.outputStream().use { output ->
                    if (onBytes == null) {
                        input.copyTo(output)
                    } else {
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            done += n
                            onBytes(done, total)
                        }
                    }
                }
            }
            return Fetched(
                modified = true,
                etag = conn.getHeaderField("ETag"),
                built = DownloadStamps.isoDate(conn.getHeaderField("Last-Modified")),
            )
        } finally {
            conn.disconnect()
        }
    }

    internal fun extractEntry(zip: File, entrySuffix: String, dest: File, staging: DownloadStaging) {
        val stage = staging.file()
        try {
            ZipInputStream(zip.inputStream().buffered()).use { zis ->
                var e = zis.nextEntry
                while (e != null) {
                    if (!e.isDirectory && e.name.endsWith(entrySuffix)) {
                        stage.outputStream().use { zis.copyTo(it) }
                        staging.commit(stage, dest, allowCopy = false)
                        return
                    }
                    e = zis.nextEntry
                }
            }
        } finally {
            staging.discard(stage)
        }
        throw RuntimeException("$entrySuffix not found in ${zip.name}")
    }

    private fun extractAllInfo(zip: File, infoDir: File) {
        ZipInputStream(zip.inputStream().buffered()).use { zis ->
            var e = zis.nextEntry
            while (e != null) {
                if (!e.isDirectory && e.name.endsWith(".info")) {
                    val flatName = e.name.substringAfterLast('/')
                    if (flatName.contains("..") || flatName.contains(File.separatorChar)) {
                        e = zis.nextEntry
                        continue
                    }
                    File(infoDir, flatName).outputStream().use { zis.copyTo(it) }
                }
                e = zis.nextEntry
            }
        }
    }
}
