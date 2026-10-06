package dev.cannoli.scorza.download

class DownloadCancelled : RuntimeException()

interface DownloadHandler {
    val kind: DownloadKind

    fun run(
        item: DownloadItem,
        onProgress: (downloaded: Long, total: Long) -> Unit,
        isCancelled: () -> Boolean,
    )
}
