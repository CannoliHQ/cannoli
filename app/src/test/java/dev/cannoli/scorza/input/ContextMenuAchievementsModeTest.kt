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
        every { glvm.reload(any()) } answers { firstArg<() -> Unit>().invoke() }
        handler = testDialogInputHandler(
            nav = nav,
            ioScope = CoroutineScope(Dispatchers.Unconfined),
            context = context,
            gameListViewModel = glvm,
            romsRepository = romsRepository,
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

    private fun modeValue(rom: Rom): String? =
        handler.achievementsOptions(rom)
            .firstOrNull { it.startsWith("$MENU_ACHIEVEMENTS_MODE\t") }
            ?.substringAfter('\t')

    private fun selectRom(rom: Rom) {
        nav.screenStack.add(LauncherScreen.GameList)
        every { glvm.getSelectedItem() } returns ListItem.RomItem(rom)
        every { glvm.state } returns MutableStateFlow(
            GameListViewModel.State(items = listOf(ListItem.RomItem(rom)))
        )
    }

    @Test fun `the achievements row is absent when logged out`() {
        val loggedOut = testDialogInputHandler(
            nav = NavigationController(),
            ioScope = CoroutineScope(Dispatchers.Unconfined),
            context = context,
            gameListViewModel = glvm,
            romsRepository = romsRepository,
        )
        val options = loggedOut.buildGameContextOptions(ListItem.RomItem(rom()), GameListViewModel.State())
        assertTrue(options.none { it.substringBefore('\t') == MENU_ACHIEVEMENTS })
    }

    @Test fun `the achievements row is present when signed in`() {
        val options = handler.buildGameContextOptions(ListItem.RomItem(rom()), GameListViewModel.State())
        assertTrue(options.any { it.substringBefore('\t') == MENU_ACHIEVEMENTS })
    }

    @Test fun `the parent menu carries no achievement rows of its own`() {
        val keys = handler.buildGameContextOptions(ListItem.RomItem(rom()), GameListViewModel.State())
            .map { it.substringBefore('\t') }
        assertTrue(MENU_RA_GAME_ID !in keys)
        assertTrue(MENU_ACHIEVEMENTS_MODE !in keys)
        assertTrue(MENU_PRELOAD_ACHIEVEMENTS !in keys)
    }

    @Test fun `a game id settles the mode and says so`() {
        assertEquals(
            context.getString(dev.cannoli.ui.R.string.achievements_mode_locked),
            modeValue(rom(raGameId = 1234)),
        )
    }

    @Test fun `the mode row shows what this game states`() {
        assertEquals(context.getString(dev.cannoli.ui.R.string.achievos_mode_hardcore), modeValue(rom(raHardcore = true)))
        assertEquals(context.getString(dev.cannoli.ui.R.string.achievos_mode_softcore), modeValue(rom(raHardcore = false)))
        assertEquals(
            context.getString(
                dev.cannoli.ui.R.string.achievos_mode_use_global,
                context.getString(dev.cannoli.ui.R.string.achievos_mode_softcore),
            ),
            modeValue(rom(raHardcore = null)),
        )
    }

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
        assertEquals(
            context.getString(
                dev.cannoli.ui.R.string.achievos_mode_use_global,
                context.getString(dev.cannoli.ui.R.string.achievos_mode_hardcore),
            ),
            hardcoreGlobal.achievementsOptions(rom()).first { it.startsWith("$MENU_ACHIEVEMENTS_MODE\t") }
                .substringAfter('\t'),
        )
    }

    @Test fun `right steps the mode on`() {
        selectRom(rom(raHardcore = null))
        handler.cycleAchievementsMode(7L, 1)
        verify { romsRepository.setRaHardcore(7L, true) }
    }

    @Test fun `right from softcore returns to using the default`() {
        selectRom(rom(raHardcore = false))
        handler.cycleAchievementsMode(7L, 1)
        verify { romsRepository.setRaHardcore(7L, null) }
    }

    @Test fun `left steps the mode the other way`() {
        selectRom(rom(raHardcore = null))
        handler.cycleAchievementsMode(7L, -1)
        verify { romsRepository.setRaHardcore(7L, false) }
    }

    @Test fun `a game id makes the mode row refuse to cycle`() {
        selectRom(rom(raGameId = 1234))
        handler.cycleAchievementsMode(7L, 1)
        verify(exactly = 0) { romsRepository.setRaHardcore(any(), any()) }
    }

    @Test fun `north clears a manual game id`() {
        selectRom(rom(raGameId = 788))
        handler.openAchievementsMenu(7L)
        val picker = nav.dialogState.value as DialogState.Picker
        val idx = picker.items.indexOfFirst { it.clears }
        assertTrue("the id row must offer the clear", idx >= 0)
        nav.dialogState.value = picker.copy(selectedIndex = idx)
        handler.onNorth()
        verify { romsRepository.setRaGameId(7L, null) }
        verify { romsRepository.setRaCachedGameId(7L, null) }
    }

    @Test fun `north clears a recorded id with no override`() {
        selectRom(rom().copy(raCachedGameId = 2014))
        handler.openAchievementsMenu(7L)
        val picker = nav.dialogState.value as DialogState.Picker
        val idx = picker.items.indexOfFirst { it.clears }
        assertTrue("the id row must offer the clear", idx >= 0)
        nav.dialogState.value = picker.copy(selectedIndex = idx)
        handler.onNorth()
        verify { romsRepository.setRaCachedGameId(7L, null) }
    }

    @Test fun `the group stays bound to the rom it was opened for`() {
        val tecmo = rom(raHardcore = false).copy(id = 7L, displayName = "Tecmo")
        val pokemon = rom(raHardcore = true).copy(id = 31L, displayName = "Pokemon")
        nav.screenStack.add(LauncherScreen.GameList)
        every { glvm.state } returns MutableStateFlow(
            GameListViewModel.State(items = listOf(ListItem.RomItem(tecmo), ListItem.RomItem(pokemon)))
        )
        every { glvm.getSelectedItem() } returns ListItem.RomItem(pokemon)

        handler.openAchievementsMenu(7L)
        val modeRow = (nav.dialogState.value as DialogState.Picker).items.first { it.cycles }
        assertEquals(context.getString(dev.cannoli.ui.R.string.achievos_mode_softcore), modeRow.value)

        handler.cycleAchievementsMode(7L, 1)
        verify { romsRepository.setRaHardcore(7L, any()) }
        verify(exactly = 0) { romsRepository.setRaHardcore(31L, any()) }
    }

    @Test fun `north on the mode row clears nothing`() {
        selectRom(rom(raHardcore = false))
        handler.openAchievementsMenu(7L)
        val picker = nav.dialogState.value as DialogState.Picker
        val idx = picker.items.indexOfFirst { it.cycles }
        nav.dialogState.value = picker.copy(selectedIndex = idx)
        handler.onNorth()
        verify(exactly = 0) { romsRepository.setRaGameId(any(), any()) }
    }

    @Test fun `entering a row records a return to the group`() {
        selectRom(rom())
        handler.pendingContextReturn = ContextReturn.Single("Game", listOf(MENU_ACHIEVEMENTS), 0)
        handler.openAchievementsMenu(7L)
        (nav.dialogState.value as DialogState.Picker).onSelect(0)
        val ret = handler.pendingContextReturn as ContextReturn.Achievements
        assertEquals(MENU_RA_GAME_ID, ret.selectRow)
    }

    @Test fun `returning to the group hands the parent menu back`() {
        selectRom(rom())
        val parent = ContextReturn.Single("Game", listOf(MENU_ACHIEVEMENTS), 0)
        handler.pendingContextReturn = ContextReturn.Achievements(7L, MENU_RA_GAME_ID, parent)
        handler.restoreContextMenu()
        assertEquals(parent, handler.pendingContextReturn)
        assertTrue(nav.dialogState.value is DialogState.Picker)
    }

    @Test fun `the effective game id prefers the manual override`() {
        assertEquals(2014, effectiveRaGameId(rom(raGameId = 2014)))
        assertEquals(788, effectiveRaGameId(rom(raHardcore = null).copy(raCachedGameId = 788)))
        assertEquals(2014, effectiveRaGameId(rom(raGameId = 2014).copy(raCachedGameId = 788)))
        assertEquals(null, effectiveRaGameId(rom()))
    }

    @Test fun `preloading rebuilds the list so the row can read the new cache`() {
        val preloader = mockk<dev.cannoli.scorza.achievements.RaPreloadController>(relaxed = true)
        every { preloader.preloadRom(any(), any()) } answers { secondArg<() -> Unit>().invoke() }
        val h = testDialogInputHandler(
            nav = nav,
            ioScope = CoroutineScope(Dispatchers.Unconfined),
            context = context,
            gameListViewModel = glvm,
            romsRepository = romsRepository,
            raToken = "token",
            raPreloadController = preloader,
        )
        val rom = rom(raGameId = 788)
        selectRom(rom)
        h.preloadAchievementsFor(rom)
        verify { glvm.reload(any()) }
    }

    @Test fun `dismissing the preload result returns to the group`() {
        selectRom(rom())
        handler.pendingContextReturn = ContextReturn.Achievements(
            7L,
            MENU_PRELOAD_ACHIEVEMENTS,
            ContextReturn.Single("Game", listOf(MENU_ACHIEVEMENTS), 0),
        )
        nav.dialogState.value = DialogState.RAPreloadResult(success = true, message = "Game")
        handler.onConfirm()
        assertTrue(nav.dialogState.value is DialogState.Picker)
    }

    @Test fun `dismissing the preload result closes when nothing is pending`() {
        selectRom(rom())
        handler.pendingContextReturn = null
        nav.dialogState.value = DialogState.RAPreloadResult(success = true, message = "Game")
        handler.onConfirm()
        assertEquals(DialogState.None, nav.dialogState.value)
    }

    @Test fun `back from the group reaches the context menu`() {
        selectRom(rom())
        handler.pendingContextReturn = ContextReturn.Single("Game", listOf(MENU_ACHIEVEMENTS), 0)
        handler.openAchievementsMenu(7L)
        (nav.dialogState.value as DialogState.Picker).onBack!!.invoke()
        assertTrue(nav.dialogState.value is DialogState.ContextMenu)
    }

    @Test fun `cycling leaves the achievements menu open`() {
        selectRom(rom(raHardcore = null))
        handler.cycleAchievementsMode(7L, 1)
        assertTrue(nav.dialogState.value is DialogState.Picker)
    }
}
