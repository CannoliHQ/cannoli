package dev.cannoli.scorza.romm.sync

import dev.cannoli.ui.components.SaveSyncStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

fun resolveIdleStatus(enabled: Boolean, online: Boolean, pendingConflicts: Int, hadError: Boolean): SaveSyncStatus = when {
    !enabled -> SaveSyncStatus.DISABLED
    !online -> SaveSyncStatus.OFFLINE
    pendingConflicts > 0 -> SaveSyncStatus.CONFLICT
    hadError -> SaveSyncStatus.ERROR
    else -> SaveSyncStatus.UP_TO_DATE
}

data class SyncFailure(val displayName: String, val reason: String)

class SaveSyncStatusHolder {
    private val _state = MutableStateFlow(SaveSyncStatus.DISABLED)
    val state: StateFlow<SaveSyncStatus> = _state
    private val _errors = MutableStateFlow<List<SyncFailure>>(emptyList())
    val errors: StateFlow<List<SyncFailure>> = _errors
    fun setActive(direction: SaveSyncStatus) { _state.value = direction }
    fun setErrors(failures: List<SyncFailure>) { _errors.value = failures }
    fun settle(enabled: Boolean, online: Boolean, pendingConflicts: Int, hadError: Boolean) {
        _state.value = resolveIdleStatus(enabled, online, pendingConflicts, hadError)
    }
}
