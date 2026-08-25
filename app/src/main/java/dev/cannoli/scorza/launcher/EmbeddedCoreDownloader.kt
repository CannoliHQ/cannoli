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

    fun download(context: Context, coreName: String, forceInfoRefresh: Boolean = false): CoreDownloadService.Result {
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
            try {
                fetch(soUrl, tmp)
                extractEntry(tmp, soName, File(coresDir, soName))
            } finally {
                tmp.delete()
            }
            Log.i(TAG, "installed $soName")
            CoreDownloadService.Result("core", coreName, true, null)
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
    fun installRemoteSystemFiles(context: Context, coreName: String, biosFor: (String) -> File): Boolean {
        var allLanded = true
        for (entry in SystemFiles.remoteFor(context.assets, coreName)) {
            val tmp = File.createTempFile("system_", ".zip", context.cacheDir)
            try {
                fetch("${SystemFiles.BUILDBOT_SYSTEM}/${encode(entry.archive)}", tmp)
                val dest = biosFor(entry.tag)
                tmp.inputStream().use { SystemFiles.install(it, dest) }
                Log.i(TAG, "installed ${entry.archive} into ${dest.name}")
            } catch (t: Throwable) {
                Log.w(TAG, "system files ${entry.archive} failed for $coreName", t)
                allLanded = false
            } finally {
                tmp.delete()
            }
        }
        return allLanded
    }

    // Buildbot asset names carry spaces. URLEncoder is form encoding, which turns a space into
    // "+" and breaks the path, so only the space is replaced.
    private fun encode(name: String): String = name.replace(" ", "%20")

    private fun pickAbi(): String =
        Build.SUPPORTED_ABIS?.firstOrNull { it == "arm64-v8a" || it == "armeabi-v7a" }
            ?: "arm64-v8a"

    private fun fetch(url: String, out: File) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 60_000
        conn.instanceFollowRedirects = true
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw RuntimeException("HTTP $code for $url")
            conn.inputStream.use { input ->
                out.outputStream().use { output -> input.copyTo(output) }
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun extractEntry(zip: File, entrySuffix: String, dest: File) {
        ZipInputStream(zip.inputStream().buffered()).use { zis ->
            var e = zis.nextEntry
            while (e != null) {
                if (!e.isDirectory && e.name.endsWith(entrySuffix)) {
                    dest.outputStream().use { zis.copyTo(it) }
                    return
                }
                e = zis.nextEntry
            }
        }
        throw RuntimeException("$entrySuffix not found in ${zip.name}")
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
