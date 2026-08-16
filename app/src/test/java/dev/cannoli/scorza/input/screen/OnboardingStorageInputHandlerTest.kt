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

    @Test fun confirmFinishesWithTheResolvedPath() {
        show()
        handler.onConfirm()
        verify { onboarding.finish("/storage/emulated/0/Cannoli/") }
    }

    @Test fun confirmOpensTheBrowserForCustomWithoutAPath() {
        show(volumeIndex = 1)
        handler.onConfirm()
        assertTrue(nav.currentScreen is LauncherScreen.DirectoryBrowser)
        verify(exactly = 0) { onboarding.finish(any()) }
    }

    @Test fun confirmFinishesWithTheBrowsedPath() {
        show(volumeIndex = 1, customPath = "/storage/picked/")
        handler.onConfirm()
        verify { onboarding.finish("/storage/picked/") }
    }

    @Test fun leftAndRightCycleTheVolume() {
        show()
        handler.onRight()
        assertEquals(1, (nav.currentScreen as LauncherScreen.OnboardingStorage).volumeIndex)
        handler.onLeft()
        assertEquals(0, (nav.currentScreen as LauncherScreen.OnboardingStorage).volumeIndex)
    }

    @Test fun backReturnsToThePermissionsStep() {
        show()
        handler.onBack()
        verify { onboarding.show(OnboardingStep.PERMISSIONS) }
    }
}
