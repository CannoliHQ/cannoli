package dev.cannoli.scorza.setup

import android.content.Context
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SetupCoordinatorTest {

    private val coordinator = SetupCoordinator(mockk<Context>(relaxed = true))

    private fun tempDir(): File = Files.createTempDirectory("cannoli-test").toFile()

    private fun withCannoli(parent: File): File {
        File(parent, "Cannoli/Config").mkdirs()
        File(parent, "Cannoli/Config/settings.json").writeText("{}")
        return parent
    }

    @Test fun returnsIndexOfVolumeHoldingExistingCannoli() {
        val internal = tempDir()
        val sd = withCannoli(tempDir())
        val volumes = listOf(
            "Internal Storage" to internal.absolutePath + "/",
            "SD card" to sd.absolutePath + "/",
            "Custom" to "",
        )
        assertEquals(1, coordinator.existingCannoliVolumeIndex(volumes))
    }

    @Test fun prefersRemovableVolumeWhenBothHaveCannoli() {
        val internal = withCannoli(tempDir())
        val sd = withCannoli(tempDir())
        val volumes = listOf(
            "Internal Storage" to internal.absolutePath + "/",
            "SD card" to sd.absolutePath + "/",
            "Custom" to "",
        )
        assertEquals(1, coordinator.existingCannoliVolumeIndex(volumes))
    }

    @Test fun returnsNullWhenNoExistingCannoli() {
        val volumes = listOf(
            "Internal Storage" to tempDir().absolutePath + "/",
            "Custom" to "",
        )
        assertNull(coordinator.existingCannoliVolumeIndex(volumes))
    }

    @Test fun ignoresCustomEntryWithEmptyPath() {
        val volumes = listOf(
            "Internal Storage" to tempDir().absolutePath + "/",
            "Custom" to "",
        )
        assertNull(coordinator.existingCannoliVolumeIndex(volumes))
    }
}
