package dev.cannoli.scorza.input

import androidx.test.core.app.ApplicationProvider
import dev.cannoli.scorza.navigation.NavigationController
import dev.cannoli.scorza.ui.quickmenu.QuickMenuRow
import dev.cannoli.scorza.ui.screens.DialogState
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class QuickMenuSyncUnlocksTest {

    private val drainer: dev.cannoli.scorza.achievements.RaPendingDrainer = mockk(relaxed = true)

    private fun handler(dispatcher: TestDispatcher, nav: NavigationController) =
        testDialogInputHandler(
            nav = nav,
            ioScope = CoroutineScope(dispatcher),
            context = ApplicationProvider.getApplicationContext(),
            raPendingDrainer = drainer,
        )

    private fun menuWith(row: QuickMenuRow) = DialogState.QuickMenu(
        rows = listOf(row),
        kitchenRunning = false,
        selectedIndex = 0,
        pendingUnlockCount = 3,
    )

    @Test fun `confirming the row sends the queue`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val nav = NavigationController()
        val h = handler(dispatcher, nav)
        nav.dialogState.value = menuWith(QuickMenuRow.UNSYNCED_UNLOCKS)

        h.onConfirm()
        advanceUntilIdle()

        coVerify(exactly = 1) { drainer.drain() }
    }

    // The quick menu's other rows leave the menu behind; this one stays and rebuilds, because the
    // row carries the count and is the only thing that reports what the drain achieved. Asserting
    // the rebuild rather than merely that a menu is open: the menu was already open.
    @Test fun `confirming the row rebuilds the menu around the emptied queue`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val nav = NavigationController()
        val h = handler(dispatcher, nav)
        nav.dialogState.value = menuWith(QuickMenuRow.UNSYNCED_UNLOCKS)

        h.onConfirm()
        advanceUntilIdle()
        // openQuickMenu publishes on the main dispatcher, which Robolectric holds until idled.
        shadowOf(android.os.Looper.getMainLooper()).idle()

        val menu = nav.dialogState.value as DialogState.QuickMenu
        assertEquals(0, menu.pendingUnlockCount)
        assertFalse(menu.rows.contains(QuickMenuRow.UNSYNCED_UNLOCKS))
    }

    @Test fun `a row that is not the unlock queue does not send it`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val nav = NavigationController()
        val h = handler(dispatcher, nav)
        nav.dialogState.value = menuWith(QuickMenuRow.ABOUT)

        h.onConfirm()
        advanceUntilIdle()

        coVerify(exactly = 0) { drainer.drain() }
    }
}
