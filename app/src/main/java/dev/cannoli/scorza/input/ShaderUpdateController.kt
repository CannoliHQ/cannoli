package dev.cannoli.scorza.input

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.scopes.ActivityScoped
import dev.cannoli.scorza.di.IoScope
import dev.cannoli.scorza.download.DownloadItem
import dev.cannoli.scorza.download.DownloadKind
import dev.cannoli.scorza.download.Downloader
import dev.cannoli.scorza.launcher.ShaderDownloadHandler
import dev.cannoli.scorza.navigation.NavigationController
import dev.cannoli.scorza.settings.SettingsRepository
import dev.cannoli.scorza.ui.screens.DialogState
import dev.cannoli.scorza.ui.viewmodel.SettingsViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@ActivityScoped
class ShaderUpdateController @Inject constructor(
    @IoScope private val ioScope: CoroutineScope,
    @ApplicationContext private val context: Context,
    private val nav: NavigationController,
    private val downloader: Downloader,
    private val settings: SettingsRepository,
    private val settingsViewModel: SettingsViewModel,
) {

    fun confirm() {
        ioScope.launch {
            val bytes = withContext(Dispatchers.IO) { totalBytes() }
            withContext(Dispatchers.Main) {
                nav.dialogState.value = DialogState.UpdateShadersConfirm(bytes, installedBytes())
            }
        }
    }

    fun start() {
        nav.dialogState.value = DialogState.None
        downloader.enqueue(
            ShaderDownloadHandler.ARCHIVES.map { (archive, label) ->
                DownloadItem(
                    key = ShaderDownloadHandler.keyFor(archive),
                    displayName = context.getString(
                        dev.cannoli.ui.R.string.download_shader_database, label,
                    ),
                    kind = DownloadKind.SHADER,
                    payload = archive,
                )
            }
        )
        dev.cannoli.scorza.download.DownloadManager.ensureStarted(context)
        settings.lastShaderUpdate = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        settingsViewModel.refreshItemsAndSettings()
    }

    private fun installedBytes(): Long {
        val dir = dev.cannoli.scorza.config.CannoliPaths(settings.sdCardRoot).shadersDir
        val cluster = try {
            android.os.StatFs(dir.parentFile?.absolutePath ?: dir.absolutePath).blockSizeLong
        } catch (_: Exception) {
            0L
        }
        if (cluster <= 0L) return FALLBACK_BYTES * 4
        return FILE_COUNT * cluster
    }

    private fun totalBytes(): Long {
        var total = 0L
        for (archive in ShaderDownloadHandler.ARCHIVES.keys) {
            total += try {
                val conn = (URL("$BASE_URL$archive.zip").openConnection() as HttpURLConnection).apply {
                    requestMethod = "HEAD"
                    connectTimeout = 8_000
                    readTimeout = 8_000
                }
                try { conn.contentLengthLong.coerceAtLeast(0L) } finally { conn.disconnect() }
            } catch (_: Exception) {
                0L
            }
        }
        return if (total > 0L) total else FALLBACK_BYTES
    }

    private companion object {
        const val BASE_URL = "https://buildbot.libretro.com/assets/frontend/"
        const val FALLBACK_BYTES = 81_000_000L

        const val FILE_COUNT = 7_300L
    }
}
