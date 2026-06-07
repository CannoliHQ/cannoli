package dev.cannoli.scorza.boot

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Watches for removable-storage mount events so boot can re-evaluate setup once a card appears.
 * Platform implementation lives in BootProviders; tests use [None].
 */
interface MountWatcher {
    fun start(onChange: () -> Unit)
    fun stop()

    object None : MountWatcher {
        override fun start(onChange: () -> Unit) {}
        override fun stop() {}
    }
}

class BootSequencer(
    private val permissionStatus: PermissionStatus,
    private val isSetupResolved: () -> Boolean,
    private val detectVolumes: () -> List<Pair<String, String>>,
    private val onSetupResolved: (root: String?) -> Unit,
    private val startStorageDependent: () -> Unit,
    private val initRunner: InitRunner,
    private val scope: CoroutineScope,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val mountWatcher: MountWatcher = MountWatcher.None,
    private val scheduleTimeout: (delayMs: Long, action: () -> Unit) -> Unit = { _, _ -> },
) {
    fun interface InitRunner {
        suspend fun run(onPhase: (BootPhase, Float, String) -> Unit): BootResult
    }

    private val _state = MutableStateFlow<BootState>(BootState.Resolving)
    val state: StateFlow<BootState> = _state.asStateFlow()

    private var initJob: Job? = null
    private var storageDependentStarted = false

    // Mount-wait bookkeeping: when setup can't be resolved yet (likely the SD card is still being
    // mounted, e.g. vold delaying the scan behind the secure keyguard), hold on the splash and wait
    // for a mount event rather than dropping the user into the storage-select wizard immediately.
    // The hold only applies while still on the splash (Resolving). Once the user is in the
    // interactive wizard, granting permission must show setup right away instead of a black wait;
    // the mount watcher stays armed so a late mount still auto-proceeds.
    private var firstUnresolvedAt = 0L
    private var hasFirstUnresolved = false
    private var mountWatcherStarted = false
    private var timeoutScheduled = false

    /** Idempotent. Call from onCreate, onResume, and every permission/picker/mount result. */
    fun advance() {
        val before = _state.value
        if (before is BootState.Initializing) return
        val hasStorage = permissionStatus.hasStorage()
        val setupResolved = isSetupResolved()
        val volumes = detectVolumes()

        val unresolvedWithStorage = hasStorage && !setupResolved
        val awaitingMount: Boolean
        if (unresolvedWithStorage) {
            if (!hasFirstUnresolved) {
                hasFirstUnresolved = true
                firstUnresolvedAt = now()
            }
            if (!mountWatcherStarted) {
                mountWatcherStarted = true
                mountWatcher.start { advance() }
            }
            awaitingMount = before is BootState.Resolving &&
                now() - firstUnresolvedAt < MOUNT_WAIT_TIMEOUT_MS
            if (awaitingMount && !timeoutScheduled) {
                timeoutScheduled = true
                scheduleTimeout(MOUNT_WAIT_TIMEOUT_MS) { advance() }
            }
        } else {
            awaitingMount = false
            stopMountWatch()
            hasFirstUnresolved = false
            firstUnresolvedAt = 0L
            timeoutScheduled = false
        }

        val after = nextState(
            current = before,
            hasStorage = hasStorage,
            setupResolved = setupResolved,
            volumes = volumes,
            awaitingMount = awaitingMount,
        )
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

    private fun stopMountWatch() {
        if (mountWatcherStarted) {
            mountWatcherStarted = false
            mountWatcher.stop()
        }
    }

    fun onStoragePermissionResult() = advance()

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
        stopMountWatch()
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
        // How long to keep the splash up waiting for a removable volume to mount before falling
        // through to the storage-select wizard. A genuine no-SD first run waits this long once; a
        // mount event resolves it instantly, and the watcher stays armed afterwards so a late
        // (keyguard-gated) mount still recovers automatically.
        const val MOUNT_WAIT_TIMEOUT_MS = 6_000L

        fun nextState(
            current: BootState,
            hasStorage: Boolean,
            setupResolved: Boolean,
            volumes: List<Pair<String, String>>,
            awaitingMount: Boolean = false,
        ): BootState {
            if (!hasStorage) {
                return BootState.NeedsPermission(storageGranted = false)
            }
            return when (current) {
                is BootState.Initializing -> current
                is BootState.Error -> current
                BootState.Ready -> BootState.Ready
                else -> when {
                    setupResolved -> BootState.Initializing(BootPhase.IMPORT, 0f, "Preparing")
                    awaitingMount -> BootState.Resolving
                    else -> BootState.NeedsSetup(volumes)
                }
            }
        }
    }
}
