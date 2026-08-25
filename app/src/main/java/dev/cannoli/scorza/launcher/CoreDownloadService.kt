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
    /** [changed] false means the server answered 304: already installed and already current. */
    data class Result(
        val kind: String,
        val core: String?,
        val ok: Boolean,
        val error: String?,
        val changed: Boolean = true,
    )

    /** What an update pass actually did, so the OSD can say it rather than guess. */
    data class UpdateSummary(val checked: Int, val updated: Int, val failed: Int)

    suspend fun downloadCore(coreId: String, forceInfoRefresh: Boolean = false): Result =
        withContext(Dispatchers.IO) {
            val result = EmbeddedCoreDownloader.download(context, coreId, forceInfoRefresh)
            if (result.ok) {
                val paths = CannoliPaths(File(settings.sdCardRoot))
                EmbeddedCoreDownloader.installRemoteSystemFiles(context, coreId) { paths.biosFor(it) }
            }
            result
        }

    /**
     * Revalidate every installed core, and the system files that came with them.
     *
     * Bundled cores are included. Extraction can overwrite one on an app update that happens to
     * carry an older build than the one fetched, but the next update corrects it, and refusing to
     * update the fifteen most-used cores to avoid that window is the worse trade.
     *
     * Nothing is compared locally. Each request carries the etag recorded at install time and the
     * server answers 304 when there is nothing new, so a pass over an up-to-date install transfers
     * no bytes at all.
     */
    suspend fun updateAll(installed: Collection<String>): UpdateSummary =
        withContext(Dispatchers.IO) {
            val paths = CannoliPaths(File(settings.sdCardRoot))
            // Once per pass, not once per core: info.zip covers every core, so a second fetch
            // would re-download the same file for nothing.
            var infoRefreshed = false
            var updated = 0
            var failed = 0
            for (coreId in installed) {
                val result = EmbeddedCoreDownloader.download(context, coreId, conditional = true)
                if (!result.ok) { failed++; continue }
                if (result.changed) updated++
                // A changed binary means the version on screen is now stale. The info files are
                // fetched once and never refreshed after that, so a pass that actually installed
                // something has to say so, or the screen keeps reporting the build it replaced.
                if (result.changed && !infoRefreshed) {
                    infoRefreshed = true
                    EmbeddedCoreDownloader.refreshInfo(context)
                }
                val system = EmbeddedCoreDownloader.installRemoteSystemFiles(
                    context, coreId, conditional = true
                ) { paths.biosFor(it) }
                if (!system.ok) failed++
                updated += system.changed
            }
            UpdateSummary(checked = installed.size, updated = updated, failed = failed)
        }
}
