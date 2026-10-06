package dev.cannoli.scorza.download

sealed interface DownloadStatus {
    data object Queued : DownloadStatus
    data class Downloading(val downloaded: Long, val total: Long) : DownloadStatus
    data object Done : DownloadStatus
    data class Failed(val reason: String) : DownloadStatus
}

enum class DownloadKind { ROM, MANUAL, FIRMWARE, CORE, SHADER }

data class DownloadItem(
    val key: String,
    val displayName: String,
    val kind: DownloadKind,
    val sizeBytes: Long = 0L,
    val tag: String = "",
    val status: DownloadStatus = DownloadStatus.Queued,
    val payload: Any? = null,
)

fun List<DownloadItem>.inDisplayOrder(): List<DownloadItem> {
    val (done, active) = partition { it.status == DownloadStatus.Done || it.status is DownloadStatus.Failed }
    return active.reversed() + done.reversed()
}
