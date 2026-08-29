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

    /** What a pass would actually fetch. [bytes] is 0 when nothing needs downloading. */
    data class Preflight(val stale: List<String>, val bytes: Long, val indexUsable: Boolean)

    /**
     * Answers what an update would cost before asking the user to approve it, so the confirmation
     * can state a real number rather than the size of what is already installed.
     *
     * Two steps, because neither alone is both cheap and correct. The index is one request for the
     * whole catalogue and narrows 29 cores to a handful of candidates without touching the network
     * again. Each candidate is then confirmed with a conditional HEAD, which is the buildbot's own
     * statement about the exact bytes on offer and also reports their size.
     *
     * The second step is not belt and braces. The index can disagree with the archive it indexes:
     * a core whose published CRC does not match the `.so` inside its own zip is reported stale on
     * every check, because downloading it records the real checksum and that still will not match.
     * The etag settles it, and costs one bodyless request per candidate.
     *
     * When the index is unreachable every core becomes a candidate, which is the behaviour before
     * any of this existed: correct, just slower to answer.
     */
    suspend fun preflight(installed: Collection<String>): Preflight = withContext(Dispatchers.IO) {
        val published = EmbeddedCoreDownloader.fetchPublishedCrcs()
        val dir = EmbeddedCoreDownloader.coresDir(context)

        val candidates = if (published.isEmpty()) installed.toList() else installed.filter { coreId ->
            val url = EmbeddedCoreDownloader.soUrlFor(coreId)
            val installedCrc = DownloadStamps.crcFor(context.filesDir, url)
            val publishedCrc = published[EmbeddedCoreDownloader.archiveNameFor(coreId)]
            // Anything unknown is a candidate: a core absent from the index or never stamped has
            // to be asked about rather than assumed current. There is deliberately no check for
            // the file being gone, because a core with no file is not in `installed` at all:
            // embeddedCores() lists the directory, so deleting a core uninstalls it rather than
            // making it stale.
            publishedCrc == null || installedCrc == null || publishedCrc != installedCrc
        }

        var bytes = 0L
        val stale = candidates.filter { coreId ->
            val url = EmbeddedCoreDownloader.soUrlFor(coreId)
            val onDisk = File(dir, "${coreId}_android.so").isFile
            // A core that is not on disk cannot be revalidated: nothing to compare, so fetch it.
            val etag = if (onDisk) DownloadStamps.etagFor(context.filesDir, url) else null
            val availability = EmbeddedCoreDownloader.checkAvailability(coreId, etag)
            if (availability.stale) bytes += availability.bytes
            availability.stale
        }
        Preflight(stale, bytes, published.isNotEmpty())
    }

    /**
     * Revalidate every installed core, and the system files that came with them.
     *
     * Bundled cores are included. Extraction can overwrite one on an app update that happens to
     * carry an older build than the one fetched, but the next update corrects it, and refusing to
     * update the fifteen most-used cores to avoid that window is the worse trade.
     *
     * Two things decide whether a core is fetched. The buildbot's published CRC of the inner `.so`
     * is compared against the one recorded at install time, and a match skips the core without any
     * request at all: a zip embeds a build timestamp, so the etag changes nightly whether or not
     * the binary did, and on 2026-08-26, 130 of 148 rebuilt cores were byte-identical. Anything the
     * CRC cannot vouch for still goes through the conditional request, which answers 304 and
     * transfers nothing when there is genuinely nothing new.
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

        // Once per pass, and not conditional on a core having changed: upstream edits the info
        // files on its own clock, so tying them to a binary replacement left them stale on every
        // night where nothing was rebuilt. Conditional, so an unchanged set costs no body.
        EmbeddedCoreDownloader.refreshInfo(context)
        // Same reasoning, and the reason this pass is cheap at all: one index names the CRC of
        // every core's binary, so a core whose rebuild changed nothing is skipped without any
        // request of its own. Empty when the index is unreachable, which falls back to etags.
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
