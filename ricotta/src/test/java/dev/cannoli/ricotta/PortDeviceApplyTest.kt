package dev.cannoli.ricotta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PortDeviceApplyTest {

    @get:Rule val tmp = TemporaryFolder()

    private val offered = setOf(1, 261)

    private fun write(file: File, text: String) {
        file.parentFile!!.mkdirs()
        file.writeText(text)
    }

    @Test fun `a stored type the core still offers is applied`() {
        assertEquals(261, EmbeddedRetroArchBridge.resolvePortDevice(261, offered, afterReset = false))
    }

    @Test fun `at startup a type the core no longer offers is skipped`() {
        assertNull(EmbeddedRetroArchBridge.resolvePortDevice(517, offered, afterReset = false))
    }

    @Test fun `at startup nothing stored leaves the port alone`() {
        assertNull(EmbeddedRetroArchBridge.resolvePortDevice(null, offered, afterReset = false))
    }

    @Test fun `after a reset a port with nothing usable stored goes back to RetroPad`() {
        assertEquals(1, EmbeddedRetroArchBridge.resolvePortDevice(null, offered, afterReset = true))
        assertEquals(1, EmbeddedRetroArchBridge.resolvePortDevice(517, offered, afterReset = true))
    }

    // Literal paths from ricotta_ra_save_override in ricotta_bridge.c, which writes these files.
    @Test fun `the core-keyed files are the ones the native writer uses`() {
        assertEquals(
            listOf(
                File("/sd/Config/Overrides/Games/PS/Game/pcsx_rearmed_libretro.cfg"),
                File("/sd/Config/Overrides/Systems/PS/pcsx_rearmed_libretro.cfg"),
            ),
            EmbeddedRetroArchBridge.coreTierFiles("/sd", "PS", "Game", "pcsx_rearmed_libretro"),
        )
    }

    @Test fun `the game file wins over the platform file`() {
        val files = EmbeddedRetroArchBridge.coreTierFiles(tmp.newFolder().absolutePath, "PS", "Game", "pcsx_rearmed_libretro")
        write(files[1], "input_libretro_device_p1 = \"1\"")
        write(files[0], "input_libretro_device_p1 = \"261\"")
        assertEquals(261, EmbeddedRetroArchBridge.storedInt(files, "input_libretro_device_p1"))
    }

    @Test fun `the platform file answers when the game file is silent`() {
        val files = EmbeddedRetroArchBridge.coreTierFiles(tmp.newFolder().absolutePath, "PS", "Game", "pcsx_rearmed_libretro")
        write(files[1], "input_libretro_device_p2 = \"261\"")
        assertEquals(261, EmbeddedRetroArchBridge.storedInt(files, "input_libretro_device_p2"))
    }
}
