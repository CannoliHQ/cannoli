package dev.cannoli.scorza.input

import androidx.test.core.app.ApplicationProvider
import dev.cannoli.scorza.navigation.NavigationController
import dev.cannoli.scorza.ui.quickmenu.QuickMenuRow
import dev.cannoli.scorza.ui.screens.DialogState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class QuickMenuSyncSavesTest {

    private fun handler(nav: NavigationController) = testDialogInputHandler(
        nav = nav,
        ioScope = CoroutineScope(Dispatchers.Unconfined),
        context = ApplicationProvider.getApplicationContext(),
    )

    private fun menu(vararg rows: QuickMenuRow) =
        DialogState.QuickMenu(rows = rows.toList(), kitchenRunning = false)

    @Test fun `north syncs and closes the menu when save sync is on`() {
        val nav = NavigationController()
        val h = handler(nav)
        var syncs = 0
        h.onSyncSavesNow = { syncs++ }
        nav.dialogState.value = menu(QuickMenuRow.SETTINGS, QuickMenuRow.SYNC_HISTORY)

        assertTrue(h.onNorth())

        assertEquals(1, syncs)
        assertEquals(DialogState.None, nav.dialogState.value)
    }

    @Test fun `north does nothing when save sync is off`() {
        val nav = NavigationController()
        val h = handler(nav)
        var syncs = 0
        h.onSyncSavesNow = { syncs++ }
        val open = menu(QuickMenuRow.SETTINGS)
        nav.dialogState.value = open

        h.onNorth()

        assertEquals(0, syncs)
        assertEquals(open, nav.dialogState.value)
    }
}
