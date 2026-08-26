package dev.cannoli.scorza.launcher

import android.content.Context
import dev.cannoli.scorza.download.DownloadCancelled
import dev.cannoli.scorza.download.DownloadHandler
import dev.cannoli.scorza.download.DownloadItem
import dev.cannoli.scorza.download.DownloadKind

/**
 * A libretro core, fetched through the same queue as everything else.
 *
 * Downloading one used to be its own path: fire and forget behind a two minute OSD that said only
 * that something was happening. Several at once left the pill fighting over which to describe, and
 * a slow one looked stuck. The queue already tracks bytes, order and cancellation for RomM, so a
 * core is another kind rather than another mechanism.
 */
class CoreDownloadHandler(private val context: Context) : DownloadHandler {

    override val kind = DownloadKind.CORE

    override fun run(
        item: DownloadItem,
        onProgress: (Long, Long) -> Unit,
        isCancelled: () -> Boolean,
    ) {
        val coreId = item.payload as? String ?: throw Exception("not a core item")
        val result = EmbeddedCoreDownloader.download(
            context = context,
            coreName = coreId,
            onBytes = { read, total ->
                // Polled here rather than inside the fetch: the transfer has no cancellation hook
                // of its own, so this is the first point that can notice and stop.
                if (isCancelled()) throw DownloadCancelled()
                onProgress(read, total)
            },
        )
        if (!result.ok) throw Exception(result.error ?: "download failed")
    }

    companion object {
        /** Stable per core, so pressing download twice on one core is one queued item, not two. */
        fun keyFor(coreId: String) = "CORE-$coreId"
    }
}
