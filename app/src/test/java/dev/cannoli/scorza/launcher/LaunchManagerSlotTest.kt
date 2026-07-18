package dev.cannoli.scorza.launcher

import dev.cannoli.scorza.model.App
import dev.cannoli.scorza.model.AppType
import dev.cannoli.scorza.model.Rom
import dev.cannoli.scorza.settings.SettingsRepository
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    private fun manager(
        root: File,
        launchState: LaunchState = mockk(relaxed = true),
        apkLauncher: ApkLauncher = mockk(relaxed = true),
    ): LaunchManager {
        val settings = mockk<SettingsRepository>(relaxed = true)
        every { settings.sdCardRoot } returns root.absolutePath
        return LaunchManager(
            context = mockk(relaxed = true),
            settings = settings,
            platformConfig = mockk(relaxed = true),
            retroArchLauncher = mockk(relaxed = true),
            emuLauncher = mockk(relaxed = true),
            apkLauncher = apkLauncher,
            launchState = launchState,
            activeMappingHolder = mockk(relaxed = true),
            activityDisplayRouter = mockk(relaxed = true),
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

    @Test
    fun `successful dispatch releases launch guard without an activity resume`() {
        val root = tmp.newFolder()
        val launchState = LaunchState()
        val apkLauncher = mockk<ApkLauncher>(relaxed = true)
        every { apkLauncher.launch("dev.example.game") } returns LaunchResult.Success
        val mgr = manager(root, launchState, apkLauncher)

        val dialog = mgr.launchApp(
            App(1L, AppType.PORT, "Example", "dev.example.game"),
        )

        assertEquals(null, dialog)
        assertFalse(launchState.launching)
        assertTrue(launchState.gameActive.value)
    }

    @Test
    fun `tools do not start a game session`() {
        val root = tmp.newFolder()
        val launchState = LaunchState()
        val apkLauncher = mockk<ApkLauncher>(relaxed = true)
        every { apkLauncher.launch("dev.example.tool") } returns LaunchResult.Success
        val mgr = manager(root, launchState, apkLauncher)

        val dialog = mgr.launchApp(
            App(1L, AppType.TOOL, "Example", "dev.example.tool"),
        )

        assertEquals(null, dialog)
        assertFalse(launchState.launching)
        assertFalse(launchState.gameActive.value)
    }
}
