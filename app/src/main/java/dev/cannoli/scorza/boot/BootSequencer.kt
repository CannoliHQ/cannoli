package dev.cannoli.scorza.boot

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BootSequencer(
    private val permissionStatus: PermissionStatus,
    private val isSetupResolved: () -> Boolean,
    private val detectVolumes: () -> List<Pair<String, String>>,
    private val onSetupResolved: (root: String?) -> Unit,
    private val startStorageDependent: () -> Unit,
    private val initRunner: InitRunner,
    private val scope: CoroutineScope,
) {
    fun interface InitRunner {
        suspend fun run(onPhase: (BootPhase, Float, String) -> Unit): BootResult
    }

    private val _state = MutableStateFlow<BootState>(BootState.Resolving)
    val state: StateFlow<BootState> = _state.asStateFlow()

    private var initJob: Job? = null
    private var storageDependentStarted = false
    private var permissionStepShown = false
    private var permissionsConfirmed = false

    /** Idempotent. Call from onCreate, onResume, and every permission/picker result. */
    fun advance() {
        val before = _state.value
        if (before is BootState.Initializing) return
        var after = nextState(
            current = before,
            hasStorage = permissionStatus.hasStorage(),
            hasBluetooth = permissionStatus.hasBluetooth(),
            setupResolved = isSetupResolved(),
            volumes = detectVolumes(),
        )
        // Once the permissions wizard has been shown, hold on it (all granted) until the user
        // presses Continue, rather than auto-advancing the instant the last grant lands.
        if (after is BootState.NeedsPermission) {
            permissionStepShown = true
        } else if (permissionStepShown && !permissionsConfirmed) {
            after = BootState.NeedsPermission(storageGranted = true, bluetoothGranted = true)
        }
        when (after) {
            is BootState.NeedsPermission, is BootState.NeedsSetup, is BootState.Error, BootState.Ready, BootState.Resolving -> {
                _state.value = after
            }
            is BootState.Initializing -> {
                if (!storageDependentStarted) {
                    storageDependentStarted = true
                    startStorageDependent()
                }
                startInitialization()
            }
        }
    }

    fun onStoragePermissionResult() = advance()
    fun onBluetoothPermissionResult(@Suppress("UNUSED_PARAMETER") granted: Boolean) = advance()
    fun onInstallFinished() = advance()

    fun confirmPermissions() {
        permissionsConfirmed = true
        advance()
    }

    fun retry() {
        if (_state.value is BootState.Error) {
            _state.value = BootState.Resolving
            advance()
        }
    }

    fun onFolderChosen(root: String) {
        onSetupResolved(root)
        advance()
    }

    private fun startInitialization() {
        if (initJob?.isActive == true) return
        _state.value = BootState.Initializing(BootPhase.IMPORT, 0f, "Preparing")
        initJob = scope.launch {
            val result = initRunner.run { phase, progress, label ->
                _state.value = BootState.Initializing(phase, progress, label)
            }
            withContext(Dispatchers.Main) {
                _state.value = when (result) {
                    is BootResult.Success -> BootState.Ready
                    is BootResult.Failure -> BootState.Error(result.message)
                }
            }
        }
    }

    companion object {
        fun nextState(
            current: BootState,
            hasStorage: Boolean,
            hasBluetooth: Boolean,
            setupResolved: Boolean,
            volumes: List<Pair<String, String>>,
        ): BootState {
            if (!hasStorage || !hasBluetooth) {
                return BootState.NeedsPermission(storageGranted = hasStorage, bluetoothGranted = hasBluetooth)
            }
            return when (current) {
                is BootState.Initializing -> current
                is BootState.Error -> current
                BootState.Ready -> BootState.Ready
                else -> if (setupResolved) {
                    BootState.Initializing(BootPhase.IMPORT, 0f, "Preparing")
                } else {
                    BootState.NeedsSetup(volumes)
                }
            }
        }
    }
}
