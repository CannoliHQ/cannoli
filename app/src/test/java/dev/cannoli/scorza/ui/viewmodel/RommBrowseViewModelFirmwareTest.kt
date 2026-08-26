package dev.cannoli.scorza.ui.viewmodel

import dev.cannoli.scorza.romm.RommFirmware
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RommBrowseViewModelFirmwareTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun vm(fw: List<RommFirmware>, biosDir: File) = RommBrowseViewModel(
        library = io.mockk.mockk(relaxed = true),
        syncCoordinator = null,
        db = null,
        presentNamesFor = { emptySet() },
        linkedIdsProvider = { emptySet() },
        firmwareFor = { fw },
        biosDirFor = { biosDir },
    )

    @Test fun `loadFirmware marks present when the file exists in the bios dir`() = runTest {
        val biosDir = tmp.newFolder("PSX")
        File(biosDir, "scph5501.bin").writeText("x")
        val rows = vm(
            listOf(RommFirmware(1, "scph5501.bin", 1, null, null, null),
                   RommFirmware(2, "scph7001.bin", 1, null, null, null)),
            biosDir,
        ).loadFirmware(7, "PSX")
        assertEquals(listOf("scph7001.bin" to false, "scph5501.bin" to true),
            rows.map { it.firmware.fileName to it.present })
    }

    @Test fun `loadFirmware returns rows in the order the screen sections render them`() = runTest {
        val biosDir = tmp.newFolder("PSX")
        File(biosDir, "scph5501.bin").writeText("x")
        File(biosDir, "scph7003.bin").writeText("x")
        val rows = vm(
            listOf(RommFirmware(1, "scph5501.bin", 1, null, null, null),
                   RommFirmware(2, "scph7001.bin", 1, null, null, null),
                   RommFirmware(3, "scph7003.bin", 1, null, null, null),
                   RommFirmware(4, "scph1001.bin", 1, null, null, null)),
            biosDir,
        ).loadFirmware(7, "PSX")
        assertEquals(
            listOf("scph7001.bin", "scph1001.bin", "scph5501.bin", "scph7003.bin"),
            rows.map { it.firmware.fileName },
        )
    }
}
