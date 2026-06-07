package dev.cannoli.scorza.boot

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class BootSequencerTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
    }

    private class FakePerms(var storage: Boolean = true) : PermissionStatus {
        override fun hasStorage() = storage
    }

    private fun sequencer(
        perms: PermissionStatus,
        setupResolved: Boolean,
        runs: AtomicInteger,
        scope: TestScope,
    ): BootSequencer {
        val initRunner = BootSequencer.InitRunner { onPhase ->
            runs.incrementAndGet()
            onPhase(BootPhase.IMPORT, 1f, "done")
            BootResult.Success
        }
        return BootSequencer(
            permissionStatus = perms,
            isSetupResolved = { setupResolved },
            detectVolumes = { listOf("Internal Storage" to "/storage/emulated/0/") },
            onSetupResolved = { _ -> },
            startStorageDependent = { },
            initRunner = initRunner,
            scope = scope,
        )
    }

    @Test fun advance_runs_initialization_once_then_ready() = runTest {
        val runs = AtomicInteger(0)
        val s = sequencer(FakePerms(), setupResolved = true, runs, scope = this)
        s.advance()
        advanceUntilIdle()
        assertEquals(1, runs.get())
        assertEquals(BootState.Ready, s.state.value)
    }

    @Test fun repeated_advance_does_not_re_run_initialization() = runTest {
        val runs = AtomicInteger(0)
        val s = sequencer(FakePerms(), setupResolved = true, runs, scope = this)
        s.advance(); s.advance(); s.advance()
        advanceUntilIdle()
        assertEquals(1, runs.get())
    }

    @Test fun missing_storage_stays_in_needs_permission() = runTest {
        val s = sequencer(FakePerms(storage = false), setupResolved = true, AtomicInteger(0), scope = this)
        s.advance()
        assertTrue(s.state.value is BootState.NeedsPermission)
    }

    private class FakeMountWatcher : MountWatcher {
        var onChange: (() -> Unit)? = null
        var started = false
        var stopped = false
        override fun start(onChange: () -> Unit) { this.onChange = onChange; started = true }
        override fun stop() { stopped = true; onChange = null }
    }

    @Test fun unresolved_holds_on_splash_then_mount_event_initializes() = runTest {
        val runs = AtomicInteger(0)
        var resolved = false
        val watcher = FakeMountWatcher()
        val initRunner = BootSequencer.InitRunner { onPhase ->
            runs.incrementAndGet()
            onPhase(BootPhase.IMPORT, 1f, "done")
            BootResult.Success
        }
        val s = BootSequencer(
            permissionStatus = FakePerms(),
            isSetupResolved = { resolved },
            detectVolumes = { listOf("Internal Storage" to "/storage/emulated/0/") },
            onSetupResolved = { _ -> },
            startStorageDependent = { },
            initRunner = initRunner,
            scope = this,
            now = { 0L },
            mountWatcher = watcher,
        )
        s.advance()
        assertEquals(BootState.Resolving, s.state.value)
        assertTrue(watcher.started)
        // SD card finishes mounting: setup now resolves and the mount callback re-advances.
        resolved = true
        watcher.onChange?.invoke()
        advanceUntilIdle()
        assertEquals(BootState.Ready, s.state.value)
        assertEquals(1, runs.get())
        assertTrue(watcher.stopped)
    }

    @Test fun unresolved_falls_through_to_needs_setup_after_timeout() = runTest {
        var nowMs = 0L
        val s = BootSequencer(
            permissionStatus = FakePerms(),
            isSetupResolved = { false },
            detectVolumes = { listOf("Internal Storage" to "/storage/emulated/0/") },
            onSetupResolved = { _ -> },
            startStorageDependent = { },
            initRunner = BootSequencer.InitRunner { BootResult.Success },
            scope = this,
            now = { nowMs },
            mountWatcher = FakeMountWatcher(),
        )
        s.advance()
        assertEquals(BootState.Resolving, s.state.value)
        nowMs = BootSequencer.MOUNT_WAIT_TIMEOUT_MS + 1
        s.advance()
        assertTrue(s.state.value is BootState.NeedsSetup)
    }

    @Test fun granting_permission_from_wizard_shows_setup_without_black_splash() = runTest {
        val perms = FakePerms(storage = false)
        var scheduledCount = 0
        val watcher = FakeMountWatcher()
        val s = BootSequencer(
            permissionStatus = perms,
            isSetupResolved = { false },
            detectVolumes = { listOf("Internal Storage" to "/storage/emulated/0/") },
            onSetupResolved = { _ -> },
            startStorageDependent = { },
            initRunner = BootSequencer.InitRunner { BootResult.Success },
            scope = this,
            now = { 0L },
            mountWatcher = watcher,
            scheduleTimeout = { _, _ -> scheduledCount++ },
        )
        // Wizard is showing the permission step.
        s.advance()
        assertTrue(s.state.value is BootState.NeedsPermission)
        // User grants storage in the system prompt and returns.
        perms.storage = true
        s.advance()
        // Setup must appear immediately, never a black mount-wait splash.
        assertTrue(s.state.value is BootState.NeedsSetup)
        assertEquals(0, scheduledCount)
        // The watcher stays armed so a late SD mount can still auto-proceed.
        assertTrue(watcher.started)
    }

    @Test fun setup_unresolved_goes_to_needs_setup_then_folder_choice_initializes() = runTest {
        val runs = AtomicInteger(0)
        var resolved = false
        var nowMs = 0L
        val perms = FakePerms()
        val initRunner = BootSequencer.InitRunner { onPhase ->
            runs.incrementAndGet()
            onPhase(BootPhase.IMPORT, 1f, "done")
            BootResult.Success
        }
        val s = BootSequencer(
            permissionStatus = perms,
            isSetupResolved = { resolved },
            detectVolumes = { listOf("Internal Storage" to "/storage/emulated/0/") },
            onSetupResolved = { _ -> resolved = true },
            startStorageDependent = { },
            initRunner = initRunner,
            scope = this,
            now = { nowMs },
            mountWatcher = FakeMountWatcher(),
        )
        s.advance()
        // Past the mount wait with no card: drops to the storage-select wizard.
        nowMs = BootSequencer.MOUNT_WAIT_TIMEOUT_MS + 1
        s.advance()
        assertTrue(s.state.value is BootState.NeedsSetup)
        s.onFolderChosen("/storage/emulated/0/Cannoli/")
        advanceUntilIdle()
        assertEquals(BootState.Ready, s.state.value)
        assertEquals(1, runs.get())
    }
}
