package dev.cannoli.scorza.updater

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.StatFs
import android.text.format.Formatter
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.cannoli.scorza.BuildConfig
import dev.cannoli.scorza.R
import dev.cannoli.scorza.settings.SettingsRepository
import dev.cannoli.scorza.util.StorageLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UpdateManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository
) {
    private val _updateAvailable = MutableStateFlow<UpdateInfo?>(loadCached())
    val updateAvailable: StateFlow<UpdateInfo?> = _updateAvailable

    private val _downloadProgress = MutableStateFlow(-1f)
    val downloadProgress: StateFlow<Float> = _downloadProgress

    private val _downloadError = MutableStateFlow<String?>(null)
    val downloadError: StateFlow<String?> = _downloadError

    @Volatile
    private var downloadCancelled = false

    private val checking = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun loadCached(): UpdateInfo? {
        val code = settings.cachedUpdateCode
        if (code <= BuildConfig.VERSION_CODE) return null
        val version = settings.cachedUpdateVersion
        val tag = settings.cachedUpdateTag
        val apk = settings.cachedUpdateApk
        val changelog = settings.cachedUpdateChangelog
        if (version.isEmpty() || tag.isEmpty() || apk.isEmpty()) return null
        return UpdateInfo(version, code, tag, apk, changelog)
    }

    private fun cacheUpdate(info: UpdateInfo?) {
        if (info != null) {
            settings.cachedUpdateVersion = info.versionName
            settings.cachedUpdateCode = info.versionCode
            settings.cachedUpdateTag = info.tag
            settings.cachedUpdateApk = info.apk
            settings.cachedUpdateChangelog = info.changelog
        } else {
            settings.cachedUpdateVersion = ""
            settings.cachedUpdateCode = 0
            settings.cachedUpdateTag = ""
            settings.cachedUpdateApk = ""
            settings.cachedUpdateChangelog = ""
        }
    }

    fun shouldAutoCheck(): Boolean {
        if (!isOnline()) return false
        val elapsed = System.currentTimeMillis() - settings.lastUpdateCheck
        return elapsed > 2 * 60 * 60 * 1000L
    }

    suspend fun checkForUpdate(): UpdateInfo? = withContext(Dispatchers.IO) {
        // Skip when offline and dedupe concurrent checks (e.g. holding Left/Right on the
        // release_channel row auto-repeats); otherwise each press would race a network fetch.
        if (!isOnline()) return@withContext _updateAvailable.value
        if (!checking.compareAndSet(false, true)) return@withContext _updateAvailable.value
        try {
            val channel = ReleaseChannel.fromString(settings.releaseChannel)
            val json = fetchJson("https://update.cannoli.dev/versions.json")
            val candidates = channel.visibleChannels().mapNotNull { ch ->
                val obj = json.optJSONObject(ch.key) ?: return@mapNotNull null
                UpdateInfo(
                    versionName = obj.getString("versionName"),
                    versionCode = obj.getInt("versionCode"),
                    tag = obj.getString("tag"),
                    apk = obj.getString("apk"),
                    changelog = obj.optString("changelog", "")
                )
            }
            settings.lastUpdateCheck = System.currentTimeMillis()
            val result = candidates
                .filter { it.versionCode > BuildConfig.VERSION_CODE }
                .maxByOrNull { it.versionCode }
            cacheUpdate(result)
            _updateAvailable.value = result
            result
        } catch (_: Exception) {
            null
        } finally {
            checking.set(false)
        }
    }

    // The install runs in the system installer after downloadAndInstall returns, so the APK
    // cannot be deleted at the call site. Nothing else reclaims it, and the filename carries
    // the version, so every update used to strand another ~300 MB here. No install from this
    // process can be in flight at startup, which makes it the one safe place to sweep.
    suspend fun purgeStaleDownloads() = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates")
        val freed = dir.listFiles().orEmpty().sumOf { f ->
            val size = f.length()
            if (f.isFile && f.delete()) size else 0L
        }
        if (freed > 0) StorageLog.write("purged $freed bytes of stale update downloads")
    }

    suspend fun downloadAndInstall(info: UpdateInfo) = withContext(Dispatchers.IO) {
        downloadCancelled = false
        _downloadError.value = null
        _downloadProgress.value = 0f
        val cacheDir = File(context.cacheDir, "updates")
        cacheDir.mkdirs()
        val apkFile = File(cacheDir, info.apk)
        try {
            val url = URL(info.downloadUrl)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.connect()
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                _downloadError.value = context.getString(R.string.update_download_error_server)
                _downloadProgress.value = -1f
                return@withContext
            }
            val total = conn.contentLength.toLong()
            val available = availableBytes()
            if (!hasRoom(total, available)) {
                _downloadError.value = context.getString(
                    R.string.update_download_error_space,
                    Formatter.formatShortFileSize(context, requiredFor(total)),
                    Formatter.formatShortFileSize(context, available),
                )
                _downloadProgress.value = -1f
                return@withContext
            }
            var downloaded = 0L
            conn.inputStream.use { input ->
                apkFile.outputStream().use { output ->
                    val buf = ByteArray(8192)
                    while (true) {
                        if (downloadCancelled) {
                            apkFile.delete()
                            _downloadProgress.value = -1f
                            return@withContext
                        }
                        val read = input.read(buf)
                        if (read == -1) break
                        output.write(buf, 0, read)
                        downloaded += read
                        if (total > 0) _downloadProgress.value = downloaded.toFloat() / total
                    }
                }
            }
            _downloadProgress.value = 1f
            withContext(Dispatchers.Main) { installApk(apkFile) }
        } catch (_: Exception) {
            apkFile.delete()
            if (!downloadCancelled) {
                _downloadError.value = context.getString(R.string.update_download_error_network)
            }
            _downloadProgress.value = -1f
        }
    }

    // A StatFs failure must not block an update, so an unreadable partition reads as unlimited.
    private fun availableBytes(): Long =
        runCatching { StatFs(context.cacheDir.path).availableBytes }.getOrDefault(Long.MAX_VALUE)

    fun cancelDownload() {
        downloadCancelled = true
    }

    fun clearError() {
        _downloadError.value = null
    }

    private fun installApk(file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    fun isOnline(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    companion object {
        private const val INSTALL_HEADROOM_BYTES = 48L * 1024 * 1024

        // The system installer stages its own copy of the APK on the same partition, and the
        // old version is not released until the new one commits, so the download is only half
        // of what the install actually needs. Headroom covers dexopt output.
        internal fun requiredFor(contentLength: Long) = contentLength * 2 + INSTALL_HEADROOM_BYTES

        // A server that sends no Content-Length leaves nothing to check against, so the
        // download proceeds rather than being blocked on a number we do not have.
        internal fun hasRoom(contentLength: Long, available: Long): Boolean =
            contentLength <= 0L || available >= requiredFor(contentLength)
    }

    private fun fetchJson(urlStr: String): JSONObject {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        return try {
            conn.inputStream.bufferedReader().use { JSONObject(it.readText()) }
        } finally {
            conn.disconnect()
        }
    }
}
