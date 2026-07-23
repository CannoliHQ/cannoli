package dev.cannoli.scorza.romm.download

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class RommDownloadQueue {
    private val _state = MutableStateFlow<List<RommDownloadItem>>(emptyList())
    val state: StateFlow<List<RommDownloadItem>> = _state

    @Synchronized
    fun enqueue(items: List<RommDownloadItem>) {
        val active = _state.value
            .filter { it.status == DownloadStatus.Queued || it.status is DownloadStatus.Downloading }
            .map { it.key }.toSet()
        val fresh = items.filter { it.key !in active }.map { it.copy(status = DownloadStatus.Queued) }
        if (fresh.isEmpty()) return
        val freshKeys = fresh.map { it.key }.toSet()
        _state.value = _state.value.filterNot { it.key in freshKeys } + fresh
    }

    /** Atomically take the first queued item and mark it Downloading, so parallel workers can't claim the same rom. */
    @Synchronized
    fun claimNext(): RommDownloadItem? {
        val item = _state.value.firstOrNull { it.status == DownloadStatus.Queued } ?: return null
        _state.value = _state.value.map {
            if (it.key == item.key) it.copy(status = DownloadStatus.Downloading(0, item.sizeBytes)) else it
        }
        return item
    }

    @Synchronized
    fun setStatus(key: String, status: DownloadStatus) {
        _state.value = _state.value.map { if (it.key == key) it.copy(status = status) else it }
    }

    @Synchronized
    fun cancel(key: String) {
        _state.value = _state.value.filterNot { it.key == key }
    }

    @Synchronized
    fun cancelAll() {
        _state.value = _state.value.filter { it.status is DownloadStatus.Downloading }
    }

    @Synchronized
    fun retry(key: String) {
        _state.value = _state.value.map {
            if (it.key == key && it.status is DownloadStatus.Failed) it.copy(status = DownloadStatus.Queued) else it
        }
    }

    @Synchronized
    fun clearFinished() {
        _state.value = _state.value.filterNot {
            it.status == DownloadStatus.Done || it.status is DownloadStatus.Failed
        }
    }

    @Synchronized
    fun activeCount(): Int = _state.value.count {
        it.status == DownloadStatus.Queued || it.status is DownloadStatus.Downloading
    }
}
