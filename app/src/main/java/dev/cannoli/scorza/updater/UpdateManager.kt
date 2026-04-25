package dev.cannoli.scorza.updater

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.core.content.FileProvider
import dev.cannoli.scorza.BuildConfig
import dev.cannoli.scorza.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class UpdateManager(
    private val context: Context,
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
            val best = candidates
                .filter { it.versionCode > BuildConfig.VERSION_CODE }
                .maxByOrNull { it.versionCode }
            val result = best
            cacheUpdate(result)
            _updateAvailable.value = result
            result
        } catch (_: Exception) {
            null
        }
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
                _downloadError.value = "Download failed. Server returned an error. Try again later."
                _downloadProgress.value = -1f
                return@withContext
            }
            val total = conn.contentLength.toLong()
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
                _downloadError.value = "Download failed. Check your connection and try again."
            }
            _downloadProgress.value = -1f
        }
    }

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
