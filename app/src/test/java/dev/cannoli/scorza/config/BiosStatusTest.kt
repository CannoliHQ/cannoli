package dev.cannoli.scorza.config

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Two things a core's `.info` cannot state correctly, both hit on Neo Geo.
 *
 * FBNeo declares `fbneo/neogeo.zip` and searches three locations, the system directory last, so a
 * file at the root is found and must not read as missing. And it marks all 23 of its entries
 * optional, which is right for arcade romsets and wrong for Neo Geo, where that archive carries the
 * mandatory MVS BIOS.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BiosStatusTest {

    private fun config(): PlatformConfig {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val coreInfo = CoreInfoRepository(ctx.assets).also { it.load() }
        return PlatformConfig(File(ctx.cacheDir, "sd-root").apply { mkdirs() }, ctx.assets, coreInfo)
    }

    private fun biosDir(name: String): File {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        return File(ctx.cacheDir, "bios-$name").apply { deleteRecursively(); mkdirs() }
    }

    private fun neogeo(pc: PlatformConfig, dir: File) =
        pc.getFirmwareStatus("NEOGEO", "fbneo_libretro", dir)
            .first { File(it.first.path).name == "neogeo.zip" }

    @Test fun `firmware at the root counts as present, though declared in a subdirectory`() {
        val dir = biosDir("root")
        File(dir, "neogeo.zip").writeText("x")
        val (entry, present) = neogeo(config(), dir)
        assertTrue("declared path is a subdirectory", entry.path.contains("/"))
        assertTrue("a file at the BIOS root is found by the core", present)
    }

    @Test fun `firmware at the declared subdirectory path also counts as present`() {
        val dir = biosDir("sub")
        File(dir, "fbneo").mkdirs()
        File(dir, "fbneo/neogeo.zip").writeText("x")
        assertTrue(neogeo(config(), dir).second)
    }

    @Test fun `absent firmware is still reported missing`() {
        assertFalse(neogeo(config(), biosDir("empty")).second)
    }

    @Test fun `Neo Geo marks the MVS BIOS required even though FBNeo calls it optional`() {
        assertFalse("bios_required.txt overrides the core's flag", neogeo(config(), biosDir("req")).first.optional)
    }

    @Test fun `the same core keeps FBNeo's own flag on a platform with no override`() {
        val other = config().getFirmwareStatus("MAME", "fbneo_libretro", biosDir("mame"))
            .first { File(it.first.path).name == "neogeo.zip" }
        assertTrue("no override for MAME, so the core's optional flag stands", other.first.optional)
    }
}
