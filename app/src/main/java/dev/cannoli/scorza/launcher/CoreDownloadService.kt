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

@Singleton
class CoreDownloadService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
) {
    data class Result(
        val kind: String,
        val core: String?,
        val ok: Boolean,
        val error: String?,
        val changed: Boolean = true,
    )

    data class UpdateSummary(val checked: Int, val updated: Int, val failed: Int)

    data class UpdateProgress(
        val bytesDone: Long,
        val bytesTotal: Long,
    ) {
        val fraction: Float
            get() = if (bytesTotal <= 0L) 0f else (bytesDone.toFloat() / bytesTotal).coerceIn(0f, 1f)
    }

    private val _progress = MutableStateFlow<UpdateProgress?>(null)
    val progress: StateFlow<UpdateProgress?> = _progress

    data class Preflight(val stale: List<String>, val bytes: Long, val indexUsable: Boolean)

    suspend fun preflight(installed: Collection<String>): Preflight = withContext(Dispatchers.IO) {
        val published = EmbeddedCoreDownloader.fetchPublishedCrcs()
        val dir = EmbeddedCoreDownloader.coresDir(context)

        val candidates = if (published.isEmpty()) installed.toList() else installed.filter { coreId ->
            val url = EmbeddedCoreDownloader.soUrlFor(coreId)
            val installedCrc = DownloadStamps.crcFor(context.filesDir, url)
            val publishedCrc = published[EmbeddedCoreDownloader.archiveNameFor(coreId)]
            publishedCrc == null || installedCrc == null || publishedCrc != installedCrc
        }

        var bytes = 0L
        val stale = candidates.filter { coreId ->
            val url = EmbeddedCoreDownloader.soUrlFor(coreId)
            val onDisk = File(dir, "${coreId}_android.so").isFile
            val etag = if (onDisk) DownloadStamps.etagFor(context.filesDir, url) else null
            val availability = EmbeddedCoreDownloader.checkAvailability(coreId, etag)
            if (availability.stale) bytes += availability.bytes
            availability.stale
        }
        Preflight(stale, bytes, published.isNotEmpty())
    }

    suspend fun updateAll(installed: Collection<String>): UpdateSummary =
        withContext(Dispatchers.IO) {
        val paths = CannoliPaths(File(settings.sdCardRoot))
        val dir = EmbeddedCoreDownloader.coresDir(context)
        val weights = installed.associateWith { File(dir, "${it}_android.so").length().coerceAtLeast(1L) }
        val bytesTotal = weights.values.sum()

        EmbeddedCoreDownloader.refreshInfo(context)
        val publishedCrcs = EmbeddedCoreDownloader.fetchPublishedCrcs()
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
                context, coreId, conditional = true, publishedCrcs = publishedCrcs,
                onBytes = { read, total ->
                    val within = if (total > 0L) (read * weight / total) else 0L
                    _progress.value = UpdateProgress(bytesBase + within, bytesTotal)
                },
            )
            if (!result.ok) {
                failed++
            } else {
                if (result.changed) updated++
                val system = EmbeddedCoreDownloader.installRemoteSystemFiles(
                    context, coreId, conditional = true
                ) { paths.biosFor(it) }
                if (!system.ok) failed++
                updated += system.changed
            }
            done++
            bytesBase += weight
            _progress.value = UpdateProgress(bytesBase, bytesTotal)
        }
        completed = true
        } finally {
            withContext(NonCancellable) {
                _progress.value = null
                settings.lastCoreUpdate = java.time.LocalDate.now().toString()
                settings.lastCoreUpdateCompleted = completed
            }
        }
        UpdateSummary(checked = installed.size, updated = updated, failed = failed)
    }
}
