package dev.cannoli.scorza.config

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
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

    private fun neogeo(pc: PlatformConfig, dir: File): FirmwareRequirement.Single =
        pc.getFirmwareStatus("NEOGEO", "fbneo_libretro", dir)
            .filterIsInstance<FirmwareRequirement.Single>()
            .first { File(it.entry.path).name == "neogeo.zip" }

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
        assertTrue(neogeo(config(), dir).present)
    }

    @Test fun `absent firmware is still reported missing`() {
        assertFalse(neogeo(config(), biosDir("empty")).present)
    }

    @Test fun `Neo Geo marks the MVS BIOS required even though FBNeo calls it optional`() {
        assertFalse("bios_required.json overrides the core's flag", neogeo(config(), biosDir("req")).entry.optional)
    }

    @Test fun `the same core keeps FBNeo's own flag on a platform with no override`() {
        val other = config().getFirmwareStatus("MAME", "fbneo_libretro", biosDir("mame"))
            .filterIsInstance<FirmwareRequirement.Single>()
            .first { File(it.entry.path).name == "neogeo.zip" }
        assertTrue("no override for MAME, so the core's optional flag stands", other.entry.optional)
    }

    private fun threeDo(dir: File) =
        config().getFirmwareStatus("3DO", "opera_libretro", dir)

    // opera accepts any one of thirteen regional dumps. Asked file by file, a user holding one
    // correct BIOS reads as missing the other twelve, which is the whole reason anyOf exists.
    @Test fun `interchangeable BIOS dumps are one requirement, not thirteen`() {
        val groups = threeDo(biosDir("3do-none")).filterIsInstance<FirmwareRequirement.AnyOf>()
        assertEquals("the thirteen dumps collapse to one choice", 1, groups.size)
        assertTrue("opera declares more than one of them", groups.single().options.size > 1)
    }

    @Test fun `an anyOf group with nothing present is unsatisfied`() {
        val group = threeDo(biosDir("3do-empty")).filterIsInstance<FirmwareRequirement.AnyOf>().single()
        assertFalse(group.satisfied)
    }

    @Test fun `holding any single dump satisfies the whole group`() {
        val dir = biosDir("3do-one")
        File(dir, "goldstar.bin").writeText("x")
        val group = threeDo(dir).filterIsInstance<FirmwareRequirement.AnyOf>().single()
        assertTrue("one of the thirteen is enough", group.satisfied)
        assertEquals("only the file actually held is present", 1, group.options.count { it.second })
    }

    // The correction only ever tightens: a platform with no rule keeps whatever the core declared,
    // so nothing here can invent a requirement for a core that named none.
    @Test fun `a platform with no rule has no anyOf groups`() {
        val reqs = config().getFirmwareStatus("ATARI5200", "a5200_libretro", biosDir("a5200"))
        assertTrue(reqs.none { it is FirmwareRequirement.AnyOf })
    }
}
