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
}
