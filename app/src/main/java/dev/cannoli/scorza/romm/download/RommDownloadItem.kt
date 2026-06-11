package dev.cannoli.scorza.romm.download

import dev.cannoli.scorza.romm.RommGame

sealed interface DownloadStatus {
    data object Queued : DownloadStatus
    data class Downloading(val downloaded: Long, val total: Long) : DownloadStatus
    data object Done : DownloadStatus
    data class Failed(val reason: String) : DownloadStatus
}

enum class RommDownloadKind { ROM, MANUAL }

data class RommDownloadItem(
    val rommId: Int,
    val game: RommGame,
    val tag: String,
    val kind: RommDownloadKind = RommDownloadKind.ROM,
    val status: DownloadStatus = DownloadStatus.Queued,
) {
    val key: String get() = "${kind.name}-$rommId"
}

/**
 * Display order for the Downloads screen: active items (newest first) on top, then completed
 * items in their own section (newest first). The queue keeps insertion order for FIFO claiming,
 * so reversing here is purely presentational. Both the list renderer and the input handler must
 * use this same ordering so selection indices line up.
 */
fun List<RommDownloadItem>.inDisplayOrder(): List<RommDownloadItem> {
    val (done, active) = partition { it.status == DownloadStatus.Done }
    return active.reversed() + done.reversed()
}
