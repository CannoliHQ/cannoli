package dev.cannoli.scorza.launcher

import dev.cannoli.scorza.config.CannoliPaths
import dev.cannoli.scorza.config.PlatformConfig
import dev.cannoli.scorza.input.DeviceMapping
import dev.cannoli.scorza.input.runtime.ActiveMappingHolder
import dev.cannoli.scorza.model.Rom
import dev.cannoli.scorza.settings.SettingsRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File

/** Shared fixture for the tests that launch a ROM and read back the per-launch RetroArch config. */
abstract class LaunchConfigHarness {

    @get:Rule val tmp = TemporaryFolder()

    val settings = mockk<SettingsRepository>(relaxed = true)
    val platformConfig = mockk<PlatformConfig>(relaxed = true)
    val gameOverrides = mockk<dev.cannoli.scorza.db.GameOverrideStore>(relaxed = true)
    private val retroArchLauncher = mockk<RetroArchLauncher>(relaxed = true)

    fun manager(root: File): LaunchManager {
        every { settings.sdCardRoot } returns root.absolutePath
        every { gameOverrides.get(any()) } returns null
        every { platformConfig.getCoreName(any()) } returns "mgba_libretro"
        // Explicitly on the embedded runner, which is the path that writes a Cannoli-authored
        // config. An external RetroArch is launched by intent and never gets one.
        every { platformConfig.getPlatformChoice(any()) } returns
            dev.cannoli.scorza.config.EmulatorChoice(
                dev.cannoli.scorza.config.EmulatorSource.Embedded, "mgba_libretro",
            )
        every { platformConfig.getFirstInstalledApp(any(), any()) } returns null
        val activeMappingHolder = mockk<ActiveMappingHolder>(relaxed = true)
        every { activeMappingHolder.active } returns MutableStateFlow<DeviceMapping?>(null)
        return LaunchManager(
            context = mockk(relaxed = true),
            settings = settings,
            platformConfig = platformConfig,
            retroArchLauncher = retroArchLauncher,
            emuLauncher = mockk(relaxed = true),
            apkLauncher = mockk(relaxed = true),
            delfinoLauncher = mockk(relaxed = true),
            launchState = mockk(relaxed = true),
            activeMappingHolder = activeMappingHolder,
            atomicRename = mockk(relaxed = true),
            installedCoreService = null,
            gameOverrides = gameOverrides,
        )
    }

    fun launchedConfig(root: File, rom: Rom, mgr: LaunchManager = manager(root)): Map<String, String> =
        readLaunchConfig(root) { mgr.launchRom(rom) }

    fun resumedConfig(root: File, rom: Rom, mgr: LaunchManager = manager(root)): Map<String, String> =
        readLaunchConfig(root) { mgr.resumeRom(rom, 1) }

    private fun readLaunchConfig(root: File, launch: () -> Any?): Map<String, String> {
        val dialog = launch()
        val cfg = CannoliPaths(root.absolutePath).raLaunchCfg
        if (!cfg.exists()) throw AssertionError("no launch config written, launch returned $dialog")
        return cfg.readLines()
            .mapNotNull { line ->
                val i = line.indexOf('=')
                if (i < 0) null
                else line.take(i).trim() to line.drop(i + 1).trim().trim('"')
            }.toMap()
    }

    fun rom(
        root: File,
        relPath: String,
        tag: String,
        id: Long = 1L,
        forceSoftcore: Boolean = false,
    ): Rom {
        val file = File(root, relPath).apply { parentFile!!.mkdirs(); writeText("x") }
        return Rom(
            id = id,
            path = file,
            platformTag = tag,
            displayName = "Game",
            forceSoftcore = forceSoftcore,
        )
    }
}
