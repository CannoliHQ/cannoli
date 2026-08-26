package dev.cannoli.scorza.input

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.cannoli.scorza.db.RomsRepository
import dev.cannoli.scorza.model.ListItem
import dev.cannoli.scorza.model.Rom
import dev.cannoli.scorza.navigation.LauncherScreen
import dev.cannoli.scorza.navigation.NavigationController
import dev.cannoli.scorza.ui.screens.DialogState
import dev.cannoli.scorza.ui.viewmodel.GameListViewModel
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ContextMenuForceSoftcoreTest {

    private lateinit var nav: NavigationController
    private lateinit var glvm: GameListViewModel
    private lateinit var romsRepository: RomsRepository
    private lateinit var handler: DialogInputHandler
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before fun setup() {
        nav = NavigationController()
        glvm = mockk(relaxed = true)
        romsRepository = mockk(relaxed = true)
        every { glvm.state } returns MutableStateFlow(GameListViewModel.State())
        handler = testDialogInputHandler(
            nav = nav,
            ioScope = CoroutineScope(Dispatchers.Unconfined),
            context = context,
            gameListViewModel = glvm,
            romsRepository = romsRepository,
            // These rows only exist for a connected account, so the handler under test is one.
            raToken = "token",
        )
    }

    private fun rom(raGameId: Int? = null, forceSoftcore: Boolean = false) = Rom(
        id = 7L,
        path = File("/roms/gba/Game.gba"),
        platformTag = "gba",
        displayName = "Game",
        raGameId = raGameId,
        forceSoftcore = forceSoftcore,
    )

    private fun forceSoftcoreValue(options: List<String>): String? =
        options.firstOrNull { it.startsWith("$MENU_FORCE_SOFTCORE\t") }?.substringAfter('\t')

    @Test fun `a game id locks the force softcore row on`() {
        val options = handler.buildGameContextOptions(ListItem.RomItem(rom(raGameId = 1234)), GameListViewModel.State())
        assertEquals(context.getString(dev.cannoli.ui.R.string.force_softcore_locked), forceSoftcoreValue(options))
    }

    @Test fun `without a game id the row shows the stored toggle value`() {
        val on = handler.buildGameContextOptions(ListItem.RomItem(rom(forceSoftcore = true)), GameListViewModel.State())
        val off = handler.buildGameContextOptions(ListItem.RomItem(rom(forceSoftcore = false)), GameListViewModel.State())
        assertEquals(context.getString(dev.cannoli.ui.R.string.value_on), forceSoftcoreValue(on))
        assertEquals(context.getString(dev.cannoli.ui.R.string.value_off), forceSoftcoreValue(off))
    }

    @Test fun `confirming the locked row does not write the toggle`() {
        nav.screenStack.add(LauncherScreen.GameList)
        every { glvm.getSelectedItem() } returns ListItem.RomItem(rom(raGameId = 1234))
        nav.dialogState.value = DialogState.ContextMenu(
            gameName = "Game",
            options = listOf("$MENU_FORCE_SOFTCORE\tlocked"),
            selectedOption = 0,
        )
        handler.onConfirm()
        verify(exactly = 0) { romsRepository.setForceSoftcore(any(), any()) }
    }

    @Test fun `confirming the row without a game id writes the toggle`() {
        nav.screenStack.add(LauncherScreen.GameList)
        every { glvm.getSelectedItem() } returns ListItem.RomItem(rom(forceSoftcore = false))
        nav.dialogState.value = DialogState.ContextMenu(
            gameName = "Game",
            options = listOf("$MENU_FORCE_SOFTCORE\tOff"),
            selectedOption = 0,
        )
        handler.onConfirm()
        verify { romsRepository.setForceSoftcore(7L, true) }
    }

    // Both rows describe a RetroAchievements account: an id identifies a game to one, and softcore
    // is a property of a session. Logged out they are settings that cannot do anything, so they are
    // not offered rather than shown inert.
    @Test fun `the achievements rows are absent when logged out`() {
        val loggedOut = testDialogInputHandler(
            nav = NavigationController(),
            ioScope = CoroutineScope(Dispatchers.Unconfined),
            context = context,
            gameListViewModel = glvm,
            romsRepository = romsRepository,
            raToken = "",
        )
        val options = loggedOut.buildGameContextOptions(
            ListItem.RomItem(rom(raGameId = 1234)), GameListViewModel.State()
        )
        assertTrue(options.none { it == MENU_RA_GAME_ID || it.startsWith("$MENU_RA_GAME_ID\t") })
        assertTrue(options.none { it == MENU_FORCE_SOFTCORE || it.startsWith("$MENU_FORCE_SOFTCORE\t") })
        // The rest of the menu is untouched.
        assertTrue(options.any { it == MENU_RENAME })
    }
}
