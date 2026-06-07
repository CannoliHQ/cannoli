package dev.cannoli.scorza.input.screen

import dev.cannoli.scorza.input.ActivityActions
import dev.cannoli.scorza.input.LauncherActions
import dev.cannoli.scorza.navigation.LauncherScreen
import dev.cannoli.scorza.navigation.NavigationController
import dev.cannoli.scorza.navigation.OnboardingPermission
import dev.cannoli.scorza.settings.SettingsRepository
import dev.cannoli.scorza.setup.SetupCoordinator
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OnboardingInputHandlerTest {

    private lateinit var nav: NavigationController
    private lateinit var handler: OnboardingInputHandler
    private var chosen: String? = null
    private var requested: OnboardingPermission? = null

    private val storagePerm = listOf(OnboardingPermission.STORAGE)
    private val allGranted = storagePerm.toSet()
    private val volumes = listOf(
        "Internal Storage" to "/storage/emulated/0/",
        "Custom" to "",
    )
    private val storageRow = storagePerm.size
    private val continueRow = storagePerm.size + 1

    @Before fun setup() {
        nav = NavigationController()
        handler = OnboardingInputHandler(
            nav = nav,
            settings = mockk<SettingsRepository>(relaxed = true),
            setupCoordinator = mockk<SetupCoordinator>(relaxed = true),
            activityActions = mockk<ActivityActions>(relaxed = true),
            launcherActions = mockk<LauncherActions>(relaxed = true),
        )
        chosen = null
        requested = null
        handler.onFolderChosen = { chosen = it }
        handler.onRequestPermission = { requested = it }
    }

    private fun show(screen: LauncherScreen.OnboardingPermissions) = nav.replaceTop(screen)

    @Test fun confirmContinuesWhenContinueRowFocused() {
        show(LauncherScreen.OnboardingPermissions(
            permissions = storagePerm, granted = allGranted, volumes = volumes,
            volumeIndex = 0, selectedIndex = continueRow,
        ))
        handler.onConfirm()
        assertEquals("/storage/emulated/0/Cannoli/", chosen)
    }

    @Test fun confirmDoesNotContinueFromStorageRow() {
        show(LauncherScreen.OnboardingPermissions(
            permissions = storagePerm, granted = allGranted, volumes = volumes,
            volumeIndex = 0, selectedIndex = storageRow,
        ))
        handler.onConfirm()
        assertNull(chosen)
    }

    @Test fun confirmGrantsWhenPermissionUngranted() {
        show(LauncherScreen.OnboardingPermissions(
            permissions = storagePerm, granted = emptySet(), volumes = volumes,
            volumeIndex = 0, selectedIndex = 0,
        ))
        handler.onConfirm()
        assertEquals(OnboardingPermission.STORAGE, requested)
        assertNull(chosen)
    }

    @Test fun confirmOpensBrowserForCustomWithoutPath() {
        show(LauncherScreen.OnboardingPermissions(
            permissions = storagePerm, granted = allGranted, volumes = volumes,
            volumeIndex = 1, customPath = null, selectedIndex = storageRow,
        ))
        handler.onConfirm()
        assertTrue(nav.currentScreen is LauncherScreen.DirectoryBrowser)
        assertNull(chosen)
    }

    @Test fun confirmContinuesForCustomWithPath() {
        show(LauncherScreen.OnboardingPermissions(
            permissions = storagePerm, granted = allGranted, volumes = volumes,
            volumeIndex = 1, customPath = "/storage/picked/", selectedIndex = continueRow,
        ))
        handler.onConfirm()
        assertEquals("/storage/picked/", chosen)
    }
}
