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

    private class FakePerms(var storage: Boolean = true, var bluetooth: Boolean = true) : PermissionStatus {
        override fun hasStorage() = storage
        override fun hasBluetooth() = bluetooth
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

    @Test fun granted_permissions_hold_on_wizard_until_confirmed() = runTest {
        val runs = AtomicInteger(0)
        val perms = FakePerms(storage = false)
        val s = sequencer(perms, setupResolved = true, runs, scope = this)
        s.advance()
        assertTrue(s.state.value is BootState.NeedsPermission)

        perms.storage = true
        s.onStoragePermissionResult()
        val held = s.state.value
        assertTrue(held is BootState.NeedsPermission && held.storageGranted && held.bluetoothGranted)
        assertEquals(0, runs.get())

        s.confirmPermissions()
        advanceUntilIdle()
        assertEquals(BootState.Ready, s.state.value)
        assertEquals(1, runs.get())
    }

    @Test fun setup_unresolved_goes_to_needs_setup_then_folder_choice_initializes() = runTest {
        val runs = AtomicInteger(0)
        var resolved = false
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
        )
        s.advance()
        assertTrue(s.state.value is BootState.NeedsSetup)
        s.onFolderChosen("/storage/emulated/0/Cannoli/")
        advanceUntilIdle()
        assertEquals(BootState.Ready, s.state.value)
        assertEquals(1, runs.get())
    }
}
