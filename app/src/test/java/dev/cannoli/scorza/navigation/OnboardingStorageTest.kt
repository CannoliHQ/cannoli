package dev.cannoli.scorza.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingStorageTest {

    private val volumes = listOf(
        "Internal Storage" to "/storage/emulated/0/",
        "SD card" to "/storage/9C33-6BBD/",
        "Custom" to "",
    )

    private fun screen(
        volumes: List<Pair<String, String>> = this.volumes,
        volumeIndex: Int = 0,
        customPath: String? = null,
        existingInstallVolumeIndex: Int? = null,
    ) = LauncherScreen.OnboardingStorage(
        volumes = volumes,
        volumeIndex = volumeIndex,
        customPath = customPath,
        existingInstallVolumeIndex = existingInstallVolumeIndex,
    )

    @Test fun theEntryWithoutAPathIsTheCustomOne() {
        assertFalse(screen(volumeIndex = 0).isCustomVolume)
        assertTrue(screen(volumeIndex = 2).isCustomVolume)
    }

    @Test fun movedSelectionClampsAtBothEnds() {
        assertEquals(1, screen(volumeIndex = 0).moved(1).volumeIndex)
        assertEquals(0, screen(volumeIndex = 0).moved(-1).volumeIndex)
        assertEquals(2, screen(volumeIndex = 2).moved(1).volumeIndex)
        assertEquals(0, screen(volumes = emptyList()).moved(1).volumeIndex)
    }

    @Test fun movedSelectionKeepsTheBrowsedCustomPath() {
        val s = screen(volumeIndex = 2, customPath = "/storage/picked/")
        assertEquals("/storage/picked/", s.moved(-1).moved(1).customPath)
        assertEquals("/storage/picked/", s.moved(-1).moved(1).targetPath)
    }

    @Test fun continueNeedsAResolvedPath() {
        assertFalse(screen(volumes = emptyList()).canContinue)
        assertTrue(screen(volumeIndex = 0).canContinue)
        assertFalse(screen(volumeIndex = 2).canContinue)
        assertTrue(screen(volumeIndex = 2, customPath = "/x/").canContinue)
    }

    @Test fun targetPathPicksCustomOrAppendsCannoliFolder() {
        assertEquals("/storage/emulated/0/Cannoli/", screen(volumeIndex = 0).targetPath)
        assertEquals("/storage/9C33-6BBD/Cannoli/", screen(volumeIndex = 1).targetPath)
        assertEquals("/storage/picked/", screen(volumeIndex = 2, customPath = "/storage/picked/").targetPath)
        assertNull(screen(volumeIndex = 2).targetPath)
    }

    @Test fun existingFolderPathShowsOnlyOnTheVolumeItWasFoundOn() {
        assertEquals(
            "/storage/9C33-6BBD/Cannoli/",
            screen(volumeIndex = 1, existingInstallVolumeIndex = 1).existingFolderPath,
        )
        assertNull(screen(volumeIndex = 0, existingInstallVolumeIndex = 1).existingFolderPath)
        assertNull(screen(volumeIndex = 0).existingFolderPath)
    }

    @Test fun theFooterOffersBrowsingOnCustomAndFinishOnlyWithAPath() {
        // The footer reads these two directly: confirm browses on the custom row, START finishes.
        assertFalse(screen(volumeIndex = 0).isCustomVolume)
        assertTrue(screen(volumeIndex = 0).canContinue)
        assertTrue(screen(volumeIndex = 2).isCustomVolume)
        assertFalse(screen(volumeIndex = 2).canContinue)
        assertTrue(screen(volumeIndex = 2, customPath = "/storage/picked/").canContinue)
        assertFalse(screen(volumes = emptyList()).canContinue)
    }
}
