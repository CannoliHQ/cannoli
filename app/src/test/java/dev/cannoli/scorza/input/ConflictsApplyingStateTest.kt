package dev.cannoli.scorza.input

import androidx.test.core.app.ApplicationProvider
import dev.cannoli.scorza.navigation.NavigationController
import dev.cannoli.scorza.romm.sync.SaveSyncStatusHolder
import dev.cannoli.scorza.ui.screens.ConflictRow
import dev.cannoli.scorza.ui.screens.DialogState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConflictsApplyingStateTest {

    private lateinit var nav: NavigationController
    private lateinit var handler: DialogInputHandler

    private val dispatcher = StandardTestDispatcher()

    @Before fun setup() {
        nav = NavigationController()
        handler = testDialogInputHandler(
            nav = nav,
            ioScope = CoroutineScope(dispatcher),
            context = ApplicationProvider.getApplicationContext(),
            saveSyncStatusHolder = SaveSyncStatusHolder(),
            saveSyncService = io.mockk.mockk(relaxed = true) {
                io.mockk.every { deviceIdOrNull() } returns "dev-1"
            },
        )
    }

    private fun singleConflict() = DialogState.SaveSyncConflict(
        dev.cannoli.scorza.romm.sync.PreLaunchOutcome.Conflict(
            gameKey = "snes/Zelda.sfc",
            slot = "autosave",
            localTime = null,
            serverTime = null,
            serverDevice = null,
            saveId = 1,
            romId = 42,
            tag = "SNES",
            base = "Zelda",
            emulator = null,
        )
    )

    @Test fun `confirming a single conflict swaps to applying before the network runs`() {
        nav.dialogState.value = singleConflict()

        handler.onConfirm()

        assertTrue(
            "expected ConflictsApplying, got ${nav.dialogState.value}",
            nav.dialogState.value is DialogState.ConflictsApplying,
        )
    }

    private fun conflictsMenu() = DialogState.ConflictsMenu(
        rows = listOf(ConflictRow(gameKey = "snes/Zelda.sfc", name = "Zelda"))
    )

    @Test fun start_leaves_the_conflicts_list_immediately() {
        nav.dialogState.value = conflictsMenu()
        handler.onStart()
        assertTrue(nav.dialogState.value is DialogState.ConflictsApplying)
    }

    @Test fun a_second_start_cannot_re_apply_while_the_first_is_in_flight() {
        nav.dialogState.value = conflictsMenu()
        handler.onStart()
        handler.onStart()
        assertTrue(nav.dialogState.value is DialogState.ConflictsApplying)
    }

    @Test fun back_is_swallowed_while_applying() {
        nav.dialogState.value = DialogState.ConflictsApplying
        assertTrue(handler.onBack())
        assertTrue(nav.dialogState.value is DialogState.ConflictsApplying)
    }
}
