package dev.cannoli.scorza.input

import androidx.test.core.app.ApplicationProvider
import dev.cannoli.scorza.input.screen.ScrollListInputHandler
import dev.cannoli.scorza.navigation.LauncherScreen
import dev.cannoli.scorza.navigation.NavigationController
import dev.cannoli.scorza.settings.SettingsRepository
import dev.cannoli.scorza.ui.components.RaAccountRow
import dev.cannoli.scorza.ui.screens.DialogState
import dev.cannoli.scorza.ui.viewmodel.SettingsViewModel
import dev.cannoli.scorza.ui.viewmodel.SettingsCategory
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RetroAchievementsScreenNavTest {

    @get:Rule val tmp = TemporaryFolder()

    private val nav = NavigationController()
    private val settingsViewModel: SettingsViewModel = mockk(relaxed = true)
    private lateinit var dialogHandler: DialogInputHandler

    private fun settings(): SettingsRepository {
        File(tmp.root, "Config").mkdirs()
        File(tmp.root, "Config/settings.json").writeText("{}")
        return SettingsRepository(ApplicationProvider.getApplicationContext()).apply {
            sdCardRoot = tmp.root.absolutePath
            raUsername = "bob"
            raToken = "abc123"
            raPassword = "hunter2"
        }
    }

    private fun scrollFactory() = object : ScrollListInputHandler.Factory {
        override fun create(
            itemCount: () -> Int,
            selectedIndex: () -> Int,
            onMove: (Int) -> Unit,
            onConfirm: () -> Unit,
            onBack: () -> Unit,
            onStart: (() -> Unit)?,
            onWest: (() -> Unit)?,
            onNorth: (() -> Unit)?,
            onLeft: (() -> Unit)?,
            onRight: (() -> Unit)?,
            onSelect: (() -> Unit)?,
            onR1: (() -> Unit)?,
        ) = ScrollListInputHandler(
            nav, itemCount, selectedIndex, onMove, onConfirm, onBack,
            onStart, onWest, onNorth, onLeft, onRight, onSelect, onR1,
        )
    }

    private fun router(settings: SettingsRepository): InputRouter {
        dialogHandler = testDialogInputHandler(
            nav = nav,
            ioScope = CoroutineScope(Dispatchers.Unconfined),
            context = ApplicationProvider.getApplicationContext(),
            settingsViewModel = settingsViewModel,
        )
        return InputRouter(
        nav = nav,
        dialogHandler = dialogHandler,
        systemListHandler = mockk(relaxed = true),
        gameListHandler = mockk(relaxed = true),
        settingsHandler = mockk(relaxed = true),
        onboardingPermissionsHandler = mockk(relaxed = true),
        onboardingStorageHandler = mockk(relaxed = true),
        directoryBrowserHandler = mockk(relaxed = true),
        inputTesterHandler = mockk(relaxed = true),
        saveStatePickerHandler = mockk(relaxed = true),
        saveSlotsHandler = mockk(relaxed = true),
        guideHandler = mockk(relaxed = true),
        controllerDetailHandler = mockk(relaxed = true),
        controllersHandler = mockk(relaxed = true),
        editButtonsHandler = mockk(relaxed = true),
        loggingSettingsHandler = mockk(relaxed = true),
        permissionsHandler = mockk(relaxed = true),
        scrollListFactory = scrollFactory(),
        platformConfig = mockk(relaxed = true),
        gameOverrideStore = mockk(relaxed = true),
        emulatorMappingBuilder = mockk(relaxed = true),
        globalOverrides = mockk(relaxed = true),
        launcherActions = mockk(relaxed = true),
        bindingController = mockk(relaxed = true),
        screenInputRegistry = mockk(relaxed = true),
        context = ApplicationProvider.getApplicationContext(),
        settings = settings,
        settingsViewModel = settingsViewModel,
        coreInstaller = mockk(relaxed = true),
        rommBrowseViewModel = mockk(relaxed = true),
        rommDownloader = mockk(relaxed = true),
        osdController = mockk(relaxed = true),
        raPreloadController = mockk(relaxed = true),
        ioScope = CoroutineScope(Dispatchers.Unconfined),
        )
    }

    private fun onIntegrations() {
        every { settingsViewModel.state } returns
            MutableStateFlow(SettingsViewModel.State(activeCategory = SettingsCategory.INTEGRATIONS))
    }

    private fun onAccountScreen(s: SettingsRepository): InputRouter {
        onIntegrations()
        val r = router(s)
        r.wire(mockk(relaxed = true))
        nav.push(LauncherScreen.Settings())
        nav.push(LauncherScreen.RetroAchievements(username = "bob"))
        return r
    }

    @Test fun `confirming on the account row opens the logout confirmation`() {
        val s = settings()
        val r = onAccountScreen(s)

        r.currentHandler().onConfirm()

        assertTrue(nav.dialogState.value is DialogState.RetroAchievementsLogoutConfirm)
        assertEquals("bob", s.raUsername)
    }

    // Opened directly from the Integrations list, so Log Out clears the credentials and drops the
    // account screen back to Settings without stepping out of a credential sub-list it never entered.
    @Test fun `confirming the logout clears creds and pops the account screen`() {
        val s = settings()
        val r = onAccountScreen(s)

        r.currentHandler().onConfirm()
        dialogHandler.onConfirm()

        assertEquals("", s.raUsername)
        assertEquals("", s.raToken)
        assertEquals("", s.raPassword)
        verify { settingsViewModel.raPassword = "" }
        verify { settingsViewModel.load() }
        assertEquals(DialogState.None, nav.dialogState.value)
        assertTrue(nav.currentScreen is LauncherScreen.Settings)
    }

    @Test fun `cancelling the logout keeps the account screen and creds`() {
        val s = settings()
        val r = onAccountScreen(s)

        r.currentHandler().onConfirm()
        dialogHandler.onBack()

        assertEquals(DialogState.None, nav.dialogState.value)
        assertEquals("bob", s.raUsername)
        assertEquals("abc123", s.raToken)
        assertTrue(nav.currentScreen is LauncherScreen.RetroAchievements)
    }

    @Test fun `the west button no longer logs out`() {
        val s = settings()
        val r = onAccountScreen(s)

        r.currentHandler().onWest()

        assertEquals("bob", s.raUsername)
        assertEquals("abc123", s.raToken)
        assertTrue(nav.currentScreen is LauncherScreen.RetroAchievements)
    }

    // Bug fix: opening Offline Sets must not destroy the account screen. It stays on the stack under
    // the platforms screen, so backing out of Offline Sets lands on the account, not on Integrations.
    @Test fun `offline sets keeps the account screen so back returns to it`() {
        onIntegrations()
        val s = settings()
        val r = router(s)
        nav.push(LauncherScreen.Settings())
        nav.push(
            LauncherScreen.RetroAchievements(
                username = "bob",
                selectedIndex = RaAccountRow.entries.indexOf(RaAccountRow.OFFLINE_SETS),
            )
        )

        r.currentHandler().onConfirm()
        assertTrue(nav.currentScreen is LauncherScreen.RetroAchievementsOfflinePlatforms)
        assertTrue(nav.screenStack.any { it is LauncherScreen.RetroAchievements })

        r.currentHandler().onBack()
        assertTrue(nav.currentScreen is LauncherScreen.RetroAchievements)
    }
}
