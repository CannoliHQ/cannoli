package dev.cannoli.scorza.romm.sync

import dev.cannoli.ui.components.SaveSyncStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

fun resolveIdleStatus(enabled: Boolean, online: Boolean, pendingConflicts: Int, hadError: Boolean): SaveSyncStatus = when {
    !enabled -> SaveSyncStatus.DISABLED
    !online -> SaveSyncStatus.OFFLINE
    pendingConflicts > 0 || hadError -> SaveSyncStatus.ISSUE
    else -> SaveSyncStatus.UP_TO_DATE
}

class SaveSyncStatusHolder {
    private val _state = MutableStateFlow(SaveSyncStatus.DISABLED)
    val state: StateFlow<SaveSyncStatus> = _state
    fun setActive(direction: SaveSyncStatus) { _state.value = direction }
    fun settle(enabled: Boolean, online: Boolean, pendingConflicts: Int, hadError: Boolean) {
        _state.value = resolveIdleStatus(enabled, online, pendingConflicts, hadError)
    }
}
