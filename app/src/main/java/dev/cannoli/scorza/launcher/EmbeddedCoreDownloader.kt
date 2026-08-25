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

    fun coresDir(context: Context): File = File(context.filesDir, "cores")

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
    ): CoreDownloadService.Result {
        val abi = pickAbi()
        val coresDir = coresDir(context).apply { mkdirs() }
        val infoDir = File(context.filesDir, "info").apply { mkdirs() }
        // Accept either "snes9x" or "snes9x_libretro" — buildbot names always end in "_libretro_android.so".
        val baseName = coreName.removeSuffix("_libretro")
        val soName = "${baseName}_libretro_android.so"
        val soUrl = "$BUILDBOT/$abi/$soName.zip"

        return try {
            ensureInfoFiles(infoDir, context.cacheDir, forceInfoRefresh)

            val tmp = File.createTempFile("core_", ".zip", context.cacheDir)
            // A downloaded core's own stamp first; a bundled core has none, so the manifest the
            // build machine wrote answers for the binary the APK shipped.
            val known = if (conditional) {
                DownloadStamps.etagFor(context.filesDir, soUrl)
                    ?: BundledCoreManifest.etagFor(context.assets, coreName)
            } else null
            try {
                val result = fetch(soUrl, tmp, known)
                if (!result.modified) {
                    // Unchanged, but the date may be newly known.
                    DownloadStamps.put(context.filesDir, soUrl, result.etag, result.built)
                    Log.i(TAG, "$soName already current")
                    return CoreDownloadService.Result("core", coreName, true, null, changed = false)
                }
                extractEntry(tmp, soName, File(coresDir, soName))
                DownloadStamps.put(context.filesDir, soUrl, result.etag, result.built)
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
            fetch(INFO_ZIP_URL, tmp)
            extractAllInfo(tmp, infoDir)
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
    internal fun fetch(url: String, out: File, etag: String? = null): Fetched {
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
            conn.inputStream.use { input ->
                out.outputStream().use { output -> input.copyTo(output) }
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
