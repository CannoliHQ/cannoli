package dev.cannoli.scorza.launcher

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.cannoli.scorza.config.CannoliPaths
import dev.cannoli.scorza.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Singleton because it holds the progress of a run. Unscoped, Hilt hands every injection point its
 * own instance, so the controller updates one and the activity observes another that never changes:
 * the bar renders indeterminate and sweeps instead of filling.
 */
@Singleton
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
     * What a run is doing, for the overlay to render. The bar tracks the whole run rather than the
     * current core: weighted by size, because a count-based bar would treat a 68 MB core and a
     * 500 KB one as equal steps.
     */
    data class UpdateProgress(
        val bytesDone: Long,
        val bytesTotal: Long,
    ) {
        val fraction: Float
            get() = if (bytesTotal <= 0L) 0f else (bytesDone.toFloat() / bytesTotal).coerceIn(0f, 1f)
    }

    private val _progress = MutableStateFlow<UpdateProgress?>(null)
    val progress: StateFlow<UpdateProgress?> = _progress

    /** The size of a fresh build is close enough to the one on disk to state before downloading. */
    fun estimatedBytes(installed: Collection<String>): Long {
        val dir = EmbeddedCoreDownloader.coresDir(context)
        return installed.sumOf { File(dir, "${it}_android.so").length() }
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
     *
     * Cancelling stops before the next core. Nothing is rolled back, which is safe because each
     * core is staged and renamed, so a cancelled run leaves some cores newer and none broken.
     */
    suspend fun updateAll(installed: Collection<String>): UpdateSummary =
        withContext(Dispatchers.IO) {
        val paths = CannoliPaths(File(settings.sdCardRoot))
        val dir = EmbeddedCoreDownloader.coresDir(context)
        val weights = installed.associateWith { File(dir, "${it}_android.so").length().coerceAtLeast(1L) }
        val bytesTotal = weights.values.sum()

        // Once per pass, not once per core: info.zip covers every core, so a second fetch would
        // re-download the same file for nothing.
        var infoRefreshed = false
        var updated = 0
        var failed = 0
        var done = 0
        var bytesBase = 0L

        var completed = false
        try {
        for (coreId in installed) {
            ensureActive()
            val weight = weights[coreId] ?: 1L
            _progress.value = UpdateProgress(bytesBase, bytesTotal)
            val result = EmbeddedCoreDownloader.download(
                context, coreId, conditional = true,
                onBytes = { read, total ->
                    val within = if (total > 0L) (read * weight / total) else 0L
                    _progress.value = UpdateProgress(bytesBase + within, bytesTotal)
                },
            )
            if (!result.ok) {
                failed++
            } else {
                if (result.changed) updated++
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
            done++
            bytesBase += weight
            // Emitted after the core lands as well as before it starts. Without this the bar only
            // ever shows the work done before the current core, so it lags one behind and the last
            // core's completion never renders: it stops short and disappears.
            _progress.value = UpdateProgress(bytesBase, bytesTotal)
        }
        completed = true
        } finally {
            // Runs on the cancelled path too, and must not itself be cancelled. A stopped run still
            // replaced cores, so saying nothing happened is as wrong as claiming a full refresh.
            withContext(NonCancellable) {
                _progress.value = null
                settings.lastCoreUpdate = java.time.LocalDate.now().toString()
                settings.lastCoreUpdateCompleted = completed
            }
        }
        UpdateSummary(checked = installed.size, updated = updated, failed = failed)
    }
}
