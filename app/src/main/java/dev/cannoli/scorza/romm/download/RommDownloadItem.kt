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
