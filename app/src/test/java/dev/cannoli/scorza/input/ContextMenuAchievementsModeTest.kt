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
class ContextMenuAchievementsModeTest {

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

    private fun rom(raGameId: Int? = null, raHardcore: Boolean? = null) = Rom(
        id = 7L,
        path = File("/roms/gba/Game.gba"),
        platformTag = "gba",
        displayName = "Game",
        raGameId = raGameId,
        raHardcore = raHardcore,
    )

    private fun modeValue(options: List<String>): String? =
        options.firstOrNull { it.startsWith("$MENU_ACHIEVEMENTS_MODE\t") }?.substringAfter('\t')

    @Test fun `a game id settles the mode and says so`() {
        val options = handler.buildGameContextOptions(ListItem.RomItem(rom(raGameId = 1234)), GameListViewModel.State())
        assertEquals(context.getString(dev.cannoli.ui.R.string.achievements_mode_locked), modeValue(options))
    }

    /** All three, so a game stating softcore reads differently from one that has not chosen. */
    @Test fun `without a game id the row shows the mode this game states`() {
        fun value(mode: Boolean?) = modeValue(
            handler.buildGameContextOptions(ListItem.RomItem(rom(raHardcore = mode)), GameListViewModel.State())
        )
        assertEquals(context.getString(dev.cannoli.ui.R.string.achievos_mode_hardcore), value(true))
        assertEquals(context.getString(dev.cannoli.ui.R.string.achievos_mode_softcore), value(false))
        assertEquals(
            context.getString(
                dev.cannoli.ui.R.string.achievos_mode_use_global,
                context.getString(dev.cannoli.ui.R.string.achievos_mode_softcore),
            ),
            value(null),
        )
    }

    /** The whole point of naming it: deferring has to say what it is deferring to. */
    @Test fun `the default row names the global mode it follows`() {
        val hardcoreGlobal = testDialogInputHandler(
            nav = nav,
            ioScope = CoroutineScope(Dispatchers.Unconfined),
            context = context,
            gameListViewModel = glvm,
            romsRepository = romsRepository,
            raToken = "token",
            raHardcore = true,
        )
        val options = hardcoreGlobal.buildGameContextOptions(
            ListItem.RomItem(rom(raHardcore = null)), GameListViewModel.State()
        )
        assertEquals(
            context.getString(
                dev.cannoli.ui.R.string.achievos_mode_use_global,
                context.getString(dev.cannoli.ui.R.string.achievos_mode_hardcore),
            ),
            modeValue(options),
        )
    }

    @Test fun `cycling the locked row does not write the mode`() {
        nav.screenStack.add(LauncherScreen.GameList)
        every { glvm.getSelectedItem() } returns ListItem.RomItem(rom(raGameId = 1234))
        nav.dialogState.value = DialogState.ContextMenu(
            gameName = "Game",
            options = listOf("$MENU_ACHIEVEMENTS_MODE\tlocked"),
            selectedOption = 0,
        )
        handler.onRight()
        verify(exactly = 0) { romsRepository.setRaHardcore(any(), any()) }
    }

    /** Default steps to hardcore first, so the two modes a game can state come before the way back. */
    @Test fun `right steps the mode on`() {
        nav.screenStack.add(LauncherScreen.GameList)
        every { glvm.getSelectedItem() } returns ListItem.RomItem(rom(raHardcore = null))
        nav.dialogState.value = DialogState.ContextMenu(
            gameName = "Game",
            options = listOf("$MENU_ACHIEVEMENTS_MODE\tUse Default"),
            selectedOption = 0,
        )
        handler.onRight()
        verify { romsRepository.setRaHardcore(7L, true) }
    }

    /** The way back has to be reachable, or a game could never stop overriding the global. */
    @Test fun `right from softcore returns to using the default`() {
        nav.screenStack.add(LauncherScreen.GameList)
        every { glvm.getSelectedItem() } returns ListItem.RomItem(rom(raHardcore = false))
        nav.dialogState.value = DialogState.ContextMenu(
            gameName = "Game",
            options = listOf("$MENU_ACHIEVEMENTS_MODE\tSoftcore"),
            selectedOption = 0,
        )
        handler.onRight()
        verify { romsRepository.setRaHardcore(7L, null) }
    }

    @Test fun `left steps the mode the other way`() {
        nav.screenStack.add(LauncherScreen.GameList)
        every { glvm.getSelectedItem() } returns ListItem.RomItem(rom(raHardcore = null))
        nav.dialogState.value = DialogState.ContextMenu(
            gameName = "Game",
            options = listOf("$MENU_ACHIEVEMENTS_MODE\tUse Default"),
            selectedOption = 0,
        )
        handler.onLeft()
        verify { romsRepository.setRaHardcore(7L, false) }
    }

    /**
     * The defect this cycle shipped with: it went through restoreContextMenu, which answers a
     * return only the confirm path records, found none, and closed the menu on every press.
     */
    @Test fun `cycling leaves the menu open`() {
        nav.screenStack.add(LauncherScreen.GameList)
        every { glvm.getSelectedItem() } returns ListItem.RomItem(rom(raHardcore = null))
        nav.dialogState.value = DialogState.ContextMenu(
            gameName = "Game",
            options = listOf("$MENU_ACHIEVEMENTS_MODE\tUse Default"),
            selectedOption = 0,
        )
        handler.onRight()
        assertTrue(nav.dialogState.value is DialogState.ContextMenu)
    }

    // Both rows describe a RetroAchievements account: an id identifies a game to one, and the mode
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
        assertTrue(options.none { it == MENU_ACHIEVEMENTS_MODE || it.startsWith("$MENU_ACHIEVEMENTS_MODE\t") })
        // The rest of the menu is untouched.
        assertTrue(options.any { it == MENU_RENAME })
    }
}
