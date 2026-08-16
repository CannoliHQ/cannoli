package dev.cannoli.scorza.input.screen

import dev.cannoli.scorza.navigation.LauncherScreen
import dev.cannoli.scorza.navigation.NavigationController
import dev.cannoli.scorza.onboarding.OnboardingCoordinator
import dev.cannoli.scorza.onboarding.OnboardingStep
import dev.cannoli.scorza.setup.SetupCoordinator
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OnboardingStorageInputHandlerTest {

    private lateinit var nav: NavigationController
    private lateinit var onboarding: OnboardingCoordinator
    private lateinit var handler: OnboardingStorageInputHandler

    private val volumes = listOf(
        "Internal Storage" to "/storage/emulated/0/",
        "Custom" to "",
    )

    @Before fun setup() {
        nav = NavigationController()
        onboarding = mockk<OnboardingCoordinator>(relaxed = true)
        handler = OnboardingStorageInputHandler(
            nav = nav,
            onboarding = onboarding,
            setupCoordinator = mockk<SetupCoordinator>(relaxed = true),
        )
    }

    private fun show(volumeIndex: Int = 0, customPath: String? = null) = nav.replaceTop(
        LauncherScreen.OnboardingStorage(
            volumes = volumes,
            volumeIndex = volumeIndex,
            customPath = customPath,
        )
    )

    @Test fun startFinishesWithTheResolvedPath() {
        show()
        handler.onStart()
        verify { onboarding.finish("/storage/emulated/0/Cannoli/") }
    }

    @Test fun confirmDoesNothingOnAVolumeRow() {
        show()
        handler.onConfirm()
        verify(exactly = 0) { onboarding.finish(any()) }
        assertTrue(nav.currentScreen is LauncherScreen.OnboardingStorage)
    }

    @Test fun confirmOpensTheBrowserOnTheCustomRow() {
        show(volumeIndex = 1)
        handler.onConfirm()
        assertTrue(nav.currentScreen is LauncherScreen.DirectoryBrowser)
        verify(exactly = 0) { onboarding.finish(any()) }
    }

    @Test fun confirmReopensTheBrowserOnACustomRowThatAlreadyHasAPath() {
        show(volumeIndex = 1, customPath = "/storage/picked/")
        handler.onConfirm()
        assertTrue(nav.currentScreen is LauncherScreen.DirectoryBrowser)
    }

    @Test fun startFinishesWithTheBrowsedPath() {
        show(volumeIndex = 1, customPath = "/storage/picked/")
        handler.onStart()
        verify { onboarding.finish("/storage/picked/") }
    }

    @Test fun startDoesNothingWhileAFolderIsStillNeeded() {
        show(volumeIndex = 1)
        handler.onStart()
        verify(exactly = 0) { onboarding.finish(any()) }
        assertTrue(nav.currentScreen is LauncherScreen.OnboardingStorage)
    }

    @Test fun upAndDownMoveTheSelection() {
        show()
        handler.onDown()
        assertEquals(1, (nav.currentScreen as LauncherScreen.OnboardingStorage).volumeIndex)
        handler.onUp()
        assertEquals(0, (nav.currentScreen as LauncherScreen.OnboardingStorage).volumeIndex)
    }

    @Test fun theSelectionStopsAtTheEndsRatherThanWrapping() {
        show()
        handler.onUp()
        assertEquals(0, (nav.currentScreen as LauncherScreen.OnboardingStorage).volumeIndex)
        handler.onDown()
        handler.onDown()
        assertEquals(1, (nav.currentScreen as LauncherScreen.OnboardingStorage).volumeIndex)
    }

    @Test fun backReturnsToThePermissionsStep() {
        show()
        handler.onBack()
        verify { onboarding.show(OnboardingStep.PERMISSIONS) }
    }
}
