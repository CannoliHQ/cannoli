package dev.cannoli.scorza.download

/**
 * One queued transfer, whatever it is a transfer of.
 *
 * This started as the RomM download model and carried RomM's shape: an integer id, a game, a
 * firmware entry. Cores need the same queue and have none of those, so identity, name and size are
 * the item's own now, and whatever a handler needs to actually run the transfer rides in [payload],
 * which the queue never looks at.
 */
sealed interface DownloadStatus {
    data object Queued : DownloadStatus
    data class Downloading(val downloaded: Long, val total: Long) : DownloadStatus
    data object Done : DownloadStatus
    data class Failed(val reason: String) : DownloadStatus
}

enum class DownloadKind { ROM, MANUAL, FIRMWARE, CORE }

data class DownloadItem(
    /** Unique while queued. Two enqueues with the same key are one item, not two transfers. */
    val key: String,
    val displayName: String,
    val kind: DownloadKind,
    val sizeBytes: Long = 0L,
    /** Platform tag where one applies, so a handler knows where to install. Empty when it does not. */
    val tag: String = "",
    val status: DownloadStatus = DownloadStatus.Queued,
    /** Handed back to the handler for this kind, which is the only thing that knows its type. */
    val payload: Any? = null,
)

/**
 * Display order for the Downloads screen: active items (newest first) on top, then completed
 * items in their own section (newest first). The queue keeps insertion order for FIFO claiming,
 * so reversing here is purely presentational. Both the list renderer and the input handler must
 * use this same ordering so selection indices line up.
 */
fun List<DownloadItem>.inDisplayOrder(): List<DownloadItem> {
    val (done, active) = partition { it.status == DownloadStatus.Done || it.status is DownloadStatus.Failed }
    return active.reversed() + done.reversed()
}
