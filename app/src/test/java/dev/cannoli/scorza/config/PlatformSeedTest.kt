package dev.cannoli.scorza.config

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlatformSeedTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun config(name: String, bundled: List<String> = emptyList()): PlatformConfig {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val root = File(tmp.root, name).apply { mkdirs() }
        val libs = File(tmp.root, "$name-libs").apply { mkdirs() }
        bundled.forEach { File(libs, "${it}_android.so").writeText("stub") }
        return PlatformConfig(root, ctx.assets, nativeLibDir = libs.absolutePath).also { it.load() }
    }

    private fun pm() = ApplicationProvider.getApplicationContext<android.content.Context>().packageManager

    @Test fun `a bundled core seeds Embedded`() {
        val pc = config("seed-int", bundled = listOf("mgba_libretro"))
        pc.seedUnsetPlatforms(pm())
        assertEquals(EmulatorChoice(EmulatorSource.Embedded, "mgba_libretro"), pc.getPlatformChoice("GBA"))
    }

    @Test fun `no bundled core and no installed app leaves the platform unset`() {
        val pc = config("seed-none")
        pc.seedUnsetPlatforms(pm())
        assertNull(pc.getPlatformChoice("PS2"))
    }

    // The invariant. Seeding must never touch an existing choice, including one whose target
    // is not installed, which is the case a "repair on boot" implementation would get wrong.
    @Test fun `seeding never overwrites an explicit choice`() {
        val pc = config("seed-keep", bundled = listOf("mgba_libretro"))
        pc.setPlatformChoice("GBA", EmulatorChoice(EmulatorSource.Standalone, appPackage = "com.not.installed"))
        val before = pc.getPlatformChoice("GBA")
        pc.seedUnsetPlatforms(pm())
        assertEquals(before, pc.getPlatformChoice("GBA"))
        assertEquals(
            EmulatorChoice(EmulatorSource.Standalone, appPackage = "com.not.installed"),
            pc.getPlatformChoice("GBA"),
        )
    }

    @Test fun `an uninstalled core choice also survives seeding`() {
        val pc = config("seed-keep-core", bundled = listOf("mgba_libretro"))
        pc.setPlatformChoice("GBA", EmulatorChoice(EmulatorSource.Embedded, "gpsp_libretro"))
        pc.seedUnsetPlatforms(pm())
        assertEquals(
            EmulatorChoice(EmulatorSource.Embedded, "gpsp_libretro"),
            pc.getPlatformChoice("GBA"),
        )
    }

    @Test fun `seeding is idempotent`() {
        val pc = config("seed-idem", bundled = listOf("mgba_libretro"))
        val first = pc.seedUnsetPlatforms(pm())
        assertTrue("the first pass must seed something", first > 0)
        assertEquals(0, pc.seedUnsetPlatforms(pm()))
    }

    // Reset resolves the default immediately, so what the user sees after confirming is final
    // rather than something a later boot fills in.
    @Test fun `reset restores the bundled core straight away`() {
        val pc = config("seed-reset", bundled = listOf("mgba_libretro"))
        pc.setPlatformChoice("GBA", EmulatorChoice(EmulatorSource.Standalone, appPackage = "com.picked.emu"))
        val restored = pc.resetPlatformToDefault("GBA", pm())
        assertEquals(EmulatorChoice(EmulatorSource.Embedded, "mgba_libretro"), restored)
        assertEquals(EmulatorChoice(EmulatorSource.Embedded, "mgba_libretro"), pc.getPlatformChoice("GBA"))
    }

    @Test fun `reset on a platform with no default leaves it unmapped`() {
        val pc = config("seed-reset-none")
        pc.setPlatformChoice("PS2", EmulatorChoice(EmulatorSource.Standalone, appPackage = "com.picked.emu"))
        assertNull(pc.resetPlatformToDefault("PS2", pm()))
        assertNull(pc.getPlatformChoice("PS2"))
    }

    @Test fun `reset persists so a reload keeps the restored default`() {
        val pc = config("seed-reset-persist", bundled = listOf("mgba_libretro"))
        pc.setPlatformChoice("GBA", EmulatorChoice(EmulatorSource.Embedded, "gpsp_libretro"))
        pc.resetPlatformToDefault("GBA", pm())
        pc.reloadCoreMappings()
        assertEquals(EmulatorChoice(EmulatorSource.Embedded, "mgba_libretro"), pc.getPlatformChoice("GBA"))
    }

    @Test fun `a later release adding a bundled core backfills an untouched platform`() {
        val pc = config("seed-later")
        pc.seedUnsetPlatforms(pm())
        assertNull(pc.getPlatformChoice("GBA"))

        val libs = File(tmp.root, "seed-later-libs")
        File(libs, "mgba_libretro_android.so").writeText("stub")
        assertTrue(pc.seedUnsetPlatforms(pm()) > 0)
        assertEquals(EmulatorSource.Embedded, pc.getPlatformChoice("GBA")?.source)
    }

    @Test fun `a failed load suppresses seeding entirely`() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val root = File(tmp.root, "seed-torn").apply { mkdirs() }
        File(root, "Config").apply { mkdirs() }.also { File(it, "cores.json").writeText("{ broken") }
        val pc = PlatformConfig(root, ctx.assets).also { it.load() }
        assertEquals(0, pc.seedUnsetPlatforms(pm()))
    }

    @Test fun `a seeded choice persists across a reload`() {
        val pc = config("seed-persist", bundled = listOf("mgba_libretro"))
        pc.seedUnsetPlatforms(pm())
        pc.reloadCoreMappings()
        assertEquals(EmulatorChoice(EmulatorSource.Embedded, "mgba_libretro"), pc.getPlatformChoice("GBA"))
    }
}
