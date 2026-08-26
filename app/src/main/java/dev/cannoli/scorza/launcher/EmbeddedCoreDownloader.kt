package dev.cannoli.scorza.launcher

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

// Downloads libretro cores for the in-APK RetroArch. Ported from RicottaArch, where this ran
// behind a broadcast receiver because the launcher and RetroArch were separate apps. They are
// one app now, so the launcher calls this directly rather than broadcasting to itself.
//
// Writes to filesDir/cores, the directory findEmbeddedCore and localCores already read. The
// RicottaArch original used dataDir/cores, which is a different path.
object EmbeddedCoreDownloader {
    private const val TAG = "EmbeddedCoreDownloader"
    private const val BUILDBOT = "https://buildbot.libretro.com/nightly/android/latest"
    private const val INFO_ZIP_URL = "https://buildbot.libretro.com/assets/frontend/info.zip"
    private const val INFO_MARKER = ".cannoli_info_fetched"

    /**
     * The buildbot's per-ABI index: `<date> <crc32> <filename>`, one line per archive, every core
     * in a single request. It is the only published statement about the inner `.so` rather than the
     * zip around it, which is what makes it worth a fetch.
     */
    private fun indexUrl(abi: String) = "$BUILDBOT/$abi/.index-extended"

    fun coresDir(context: Context): File = File(context.filesDir, "cores")

    /**
     * `<date> <crc32> <filename>` per line. A line shaped differently is skipped rather than
     * guessed at: a wrong CRC here would silently skip a real update, so anything unrecognised has
     * to read as "not known" and fall through to the etag.
     */
    internal fun parseIndex(lines: Sequence<String>): Map<String, String> =
        lines.mapNotNull { line ->
            val parts = line.trim().split(' ').filter { it.isNotEmpty() }
            if (parts.size == 3 && parts[1].length == 8 && parts[1].all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) {
                parts[2] to parts[1].lowercase()
            } else null
        }.toMap()

    /**
     * The buildbot's published CRC32 for every archive of the running ABI, keyed by archive
     * filename. One request covers the whole catalogue, so an update pass fetches it once and asks
     * it about each core rather than issuing a conditional request per core.
     *
     * Returns an empty map on any failure, which reads as "nothing is known" and leaves every core
     * to the etag path it used before. A missing index must never be able to skip a download.
     */
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

    /**
     * [conditional] sends the etag recorded at install time, so an unchanged build answers 304 and
     * nothing transfers. Left off for a first install, where there is nothing to compare against
     * and a stale stamp from a deleted core would wrongly skip the download.
     */
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
        // Accept either "snes9x" or "snes9x_libretro" — buildbot names always end in "_libretro_android.so".
        val baseName = coreName.removeSuffix("_libretro")
        val soName = "${baseName}_libretro_android.so"
        val soUrl = soUrlFor(coreName)

        return try {
            ensureInfoFiles(infoDir, context.cacheDir, forceInfoRefresh)

            // The etag cannot answer this. A zip embeds a build timestamp, so it changes on every
            // nightly rebuild whether or not the core did: measured 2026-08-26, 130 of 148 rebuilt
            // cores were byte-identical, so the conditional request transferred a full binary to
            // deliver nothing. The published CRC covers the inner .so alone. Recorded at install
            // time, an unchanged one means the file on disk is already that build.
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
            // A downloaded core's own stamp first; a bundled core has none, so the manifest the
            // build machine wrote answers for the binary the APK shipped.
            val known = if (conditional) {
                DownloadStamps.etagFor(context.filesDir, soUrl)
                    ?: BundledCoreManifest.etagFor(context.assets, coreName)
            } else null
            try {
                val result = fetch(soUrl, tmp, known, onBytes)
                if (!result.modified) {
                    // Unchanged, but the date may be newly known. The checksum is read off the
                    // installed file rather than copied from the index, for the same reason as
                    // below: the stamp has to describe what this device holds, not what the
                    // catalogue claims about it.
                    DownloadStamps.put(
                        context.filesDir, soUrl, result.etag, result.built, crc32Of(soFile),
                    )
                    Log.i(TAG, "$soName already current")
                    return CoreDownloadService.Result("core", coreName, true, null, changed = false)
                }
                extractEntry(tmp, soName, soFile)
                // Stamped from the .so that landed, not from what the index promised about it.
                // The index describes the inner binary and the etag describes the zip around it,
                // published separately and observed to disagree, so only reading the installed
                // file says what this device actually holds. It also means a first install is
                // stamped without needing the index at all.
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
            // Conditional, so a pass that finds nothing new costs one small request and no body.
            // Upstream edits these on its own clock, independent of whether any binary changed, so
            // they cannot ride on a core having been replaced.
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

    /**
     * Pull the system files a downloaded core needs from the buildbot into its platform's BIOS
     * directory. Fetched here rather than bundled because these are the two sets that must not
     * ride in the APK: ScummVM's is 76 MB, and blueMSX's is original console firmware.
     *
     * Failure is not the core's failure. The core is already installed and runs; a missing engine
     * data set is a degraded platform, not a broken one, so this logs and returns.
     */
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
            // Only revalidate a set that is actually on disk. A folder the user deleted must be
            // fetched in full, which a matching etag would otherwise skip.
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

    // Buildbot asset names carry spaces. URLEncoder is form encoding, which turns a space into
    // "+" and breaks the path, so only the space is replaced.
    /** The archive name a core is published under, which is also its key in the index. */
    fun archiveNameFor(coreName: String): String =
        "${coreName.removeSuffix("_libretro")}_libretro_android.so.zip"

    /** One builder, so the stamp key and the fetch target cannot drift apart. */
    fun soUrlFor(coreName: String): String = "$BUILDBOT/${pickAbi()}/${archiveNameFor(coreName)}"

    /** Lowercase hex, to compare against the buildbot's published CRC32 of the same bytes. */
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
        // Unreadable means unknown, which costs a download rather than skipping a real one. Silent
        // by design: the caller already reports whatever went wrong with the file itself.
        null
    }

    /** [stale] false means the buildbot answered 304: the archive already downloaded is current. */
    data class Availability(val stale: Boolean, val bytes: Long)

    /**
     * Asks the buildbot directly whether a core needs fetching, with a HEAD carrying the etag
     * recorded at install time. No body either way, and a 200 reports the download size.
     *
     * This is the authority, not the index. The two can disagree: on 2026-08-26 the index
     * advertised `3d09e4a1` for nestopia while the zip it served contained a `.so` of `c33bbcc0`.
     * A CRC comparison alone then reports that core stale forever, because downloading it stamps
     * the real checksum, which still will not match what the index claims. The etag describes the
     * exact bytes on offer, so it cannot contradict itself that way.
     */
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
                // An error says nothing about freshness, so let the pass decide for itself.
                else -> Availability(true, 0L)
            }
        } finally {
            conn.disconnect()
        }
    } catch (_: Throwable) {
        Availability(true, 0L)
    }

    private fun encode(name: String): String = name.replace(" ", "%20")

    private fun pickAbi(): String =
        Build.SUPPORTED_ABIS?.firstOrNull { it == "arm64-v8a" || it == "armeabi-v7a" }
            ?: "arm64-v8a"

    /** [modified] false means the server answered 304 and [out] was not written. */
    data class Fetched(val modified: Boolean, val etag: String?, val built: String = "")

    /**
     * Fetch [url] into [out], sending [etag] as `If-None-Match` when one is known.
     *
     * The buildbot honours conditional requests on both cores and system archives, verified
     * 2026-08-25, so an unchanged file costs one small request and no body. That is what makes
     * checking every installed core affordable: ScummVM's 76 MB answers "nothing new" in 0 bytes.
     */
    // Internal rather than private so the conditional-request behaviour can be tested against a
    // real server: the 304 path is the whole mechanism, and mocking it would test the mock.
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
            // A 304 still carries the validators, so an unchanged build is where a stamp written
            // before dates were recorded gets its date. Without this those rows stay blank until
            // the core happens to change.
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
                        // Counted rather than copied wholesale: one core is 68 MB, so a bar that
                        // only moves between cores would sit still for minutes on it.
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

    /**
     * Unpack one entry over [dest] without ever leaving it partly written.
     *
     * Writing straight into [dest] truncates the core the launcher loads, so an interrupted
     * extraction leaves a file RetroArch will still try to `dlopen`. Staging beside it and renaming
     * makes the swap atomic: the destination is the old build or the new one, never half of either.
     * The stage sits in the same directory because rename is only atomic within a filesystem.
     */
    // Internal rather than private for the same reason as fetch: the failure mode is the point.
    // A test that cannot see this can only assert the happy path, which was never the risk.
    internal fun extractEntry(zip: File, entrySuffix: String, dest: File) {
        val stage = File(dest.parentFile, "${dest.name}.part")
        try {
            ZipInputStream(zip.inputStream().buffered()).use { zis ->
                var e = zis.nextEntry
                while (e != null) {
                    if (!e.isDirectory && e.name.endsWith(entrySuffix)) {
                        stage.outputStream().use { zis.copyTo(it) }
                        if (!replace(stage, dest)) throw RuntimeException("could not replace ${dest.name}")
                        return
                    }
                    e = zis.nextEntry
                }
            }
        } finally {
            stage.delete()
        }
        throw RuntimeException("$entrySuffix not found in ${zip.name}")
    }

    /**
     * Move [stage] onto [dest]. `renameTo` replaces an existing file on Android's filesystems, and
     * the fallback covers the case where it does not rather than leaving the stage orphaned.
     */
    private fun replace(stage: File, dest: File): Boolean {
        if (stage.renameTo(dest)) return true
        if (!dest.delete() && dest.exists()) return false
        return stage.renameTo(dest)
    }

    private fun extractAllInfo(zip: File, infoDir: File) {
        ZipInputStream(zip.inputStream().buffered()).use { zis ->
            var e = zis.nextEntry
            while (e != null) {
                if (!e.isDirectory && e.name.endsWith(".info")) {
                    val flatName = e.name.substringAfterLast('/')
                    // Guard against zip-slip even though we flatten.
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
