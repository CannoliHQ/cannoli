package dev.cannoli.scorza.launcher

import android.content.Context
import dev.cannoli.scorza.config.CannoliPaths
import dev.cannoli.scorza.di.CannoliPathsProvider
import dev.cannoli.scorza.download.DownloadCancelled
import dev.cannoli.scorza.download.DownloadHandler
import dev.cannoli.scorza.download.DownloadItem
import dev.cannoli.scorza.download.DownloadKind

class CoreDownloadHandler(
    private val fetchCore: (coreId: String, onBytes: (Long, Long) -> Unit) -> CoreDownloadService.Result,
    private val fetchSystemFiles: (coreId: String) -> Unit,
) : DownloadHandler {

    constructor(context: Context, paths: CannoliPathsProvider) : this(
        fetchCore = { id, onBytes -> EmbeddedCoreDownloader.download(context = context, coreName = id, onBytes = onBytes) },
        fetchSystemFiles = { id ->
            EmbeddedCoreDownloader.installRemoteSystemFiles(context, id) { CannoliPaths(paths.root).biosFor(it) }
        },
    )

    override val kind = DownloadKind.CORE

    override fun run(
        item: DownloadItem,
        onProgress: (Long, Long) -> Unit,
        isCancelled: () -> Boolean,
    ) {
        val coreId = item.payload as? String ?: throw Exception("not a core item")
        val result = fetchCore(coreId) { read, total ->
            if (isCancelled()) throw DownloadCancelled()
            onProgress(read, total)
        }
        if (!result.ok) throw Exception(result.error ?: "download failed")
        fetchSystemFiles(coreId)
    }

    companion object {
        fun keyFor(coreId: String) = "CORE-$coreId"
    }
}
