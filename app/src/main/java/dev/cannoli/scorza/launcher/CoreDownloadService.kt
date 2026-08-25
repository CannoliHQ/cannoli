package dev.cannoli.scorza.launcher

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.cannoli.scorza.config.CannoliPaths
import dev.cannoli.scorza.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

class CoreDownloadService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
) {
    data class Result(val kind: String, val core: String?, val ok: Boolean, val error: String?)

    suspend fun downloadCore(coreId: String, forceInfoRefresh: Boolean = false): Result =
        withContext(Dispatchers.IO) {
            val result = EmbeddedCoreDownloader.download(context, coreId, forceInfoRefresh)
            if (result.ok) {
                val paths = CannoliPaths(File(settings.sdCardRoot))
                EmbeddedCoreDownloader.installRemoteSystemFiles(context, coreId) { paths.biosFor(it) }
            }
            result
        }
}
