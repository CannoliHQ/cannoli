package dev.cannoli.scorza.download

/**
 * Runs one kind of transfer. The queue schedules, reports and cancels; a handler knows only how to
 * fetch and install the thing itself, and is the only place that understands an item's payload.
 *
 * Adding a kind means adding a handler and a [DownloadKind], not touching the queue or the screen
 * that renders it.
 */
/**
 * Thrown by a handler that stopped because the item was cancelled. It means the row goes away
 * rather than turning red: a cancel is not a failure, and the handler has already cleaned up
 * whatever it had staged.
 */
class DownloadCancelled : RuntimeException()

interface DownloadHandler {
    val kind: DownloadKind

    /**
     * Runs to completion or throws. [onProgress] may be called as often as bytes arrive; the queue
     * conflates. [isCancelled] is polled by long transfers so a cancel does not wait for the end.
     */
    fun run(
        item: DownloadItem,
        onProgress: (downloaded: Long, total: Long) -> Unit,
        isCancelled: () -> Boolean,
    )
}
