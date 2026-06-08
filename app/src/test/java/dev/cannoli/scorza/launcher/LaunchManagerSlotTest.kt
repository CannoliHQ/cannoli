package dev.cannoli.scorza.launcher

import dev.cannoli.scorza.model.Rom
import dev.cannoli.scorza.settings.SettingsRepository
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LaunchManagerSlotTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun rom(root: File): Rom {
        val romFile = File(root, "roms/snes/Zelda.sfc").apply { parentFile!!.mkdirs(); writeText("x") }
        return Rom(
            id = 1L,
            path = romFile,
            platformTag = "snes",
            displayName = "Zelda",
        )
    }

    private fun manager(root: File): LaunchManager {
        val settings = mockk<SettingsRepository>(relaxed = true)
        every { settings.sdCardRoot } returns root.absolutePath
        return LaunchManager(
            context = mockk(relaxed = true),
            settings = settings,
            platformConfig = mockk(relaxed = true),
            retroArchLauncher = mockk(relaxed = true),
            emuLauncher = mockk(relaxed = true),
            apkLauncher = mockk(relaxed = true),
            launchState = mockk(relaxed = true),
            activeMappingHolder = mockk(relaxed = true),
        )
    }

    @Test
    fun slotOccupancy_reflects_existing_state_files() {
        val root = tmp.newFolder()
        val mgr = manager(root)
        val r = rom(root)
        val base = mgr.saveStateBasePath(r)
        // slot index 1 == base with no suffix; slot index 3 == base + "2"
        File(base).apply { parentFile!!.mkdirs(); writeText("s") }
        File(base + "2").writeText("s")

        val occ = mgr.slotOccupancy(r)
        assertEquals(11, occ.size)
        assertTrue(occ[1])
        assertTrue(occ[3])
        assertEquals(false, occ[2])
        assertEquals(false, occ[0])
    }
}
