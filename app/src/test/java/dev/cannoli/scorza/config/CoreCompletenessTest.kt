package dev.cannoli.scorza.config

import androidx.test.core.app.ApplicationProvider
import dev.cannoli.scorza.launcher.SystemFiles
import dev.cannoli.scorza.ui.screens.CoreAvailability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * A core is installed when it can run, which for two of them means more than the `.so` being on
 * disk: blueMSX and ScummVM need system files that are a separate download, so a core with one and
 * not the other has not arrived. Reporting it installed sends the user to a launch that cannot work
 * and offers nothing to fix it.
 *
 * Bundled sets deliberately do not count. The APK carries them and the launch lays them down, so
 * their absence is never something downloading the core would repair.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CoreCompletenessTest {

    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun root(name: String): File =
        File(ctx.cacheDir, name).apply { deleteRecursively(); mkdirs() }

    private fun coresDir(root: File, vararg coreIds: String): File =
        File(root, "cores").apply {
            mkdirs()
            coreIds.forEach { File(this, "${it}_android.so").writeText("x") }
        }

    private fun config(root: File): PlatformConfig {
        val coreInfo = CoreInfoRepository(ctx.assets).also { it.load() }
        return PlatformConfig(root, ctx.assets, coreInfo)
    }

    private fun availability(root: File, tag: String, coreId: String): CoreAvailability? =
        config(root).emulatorOptionsForSource(
            tag = tag,
            source = EmulatorSource.Embedded,
            includeAll = true,
            embeddedCoresDir = coresDir(root, coreId).absolutePath,
        ).firstOrNull { it.coreId == coreId }?.availability

    private fun deliver(root: File, tag: String, vararg folders: String) {
        folders.forEach { File(root, "BIOS/$tag/$it").mkdirs() }
    }

    @Test fun `a core whose remote system files never arrived is not installed`() {
        val root = root("complete-missing")
        assertEquals(
            CoreAvailability.UNAVAILABLE,
            availability(root, "COLECOVISION", "bluemsx_libretro"),
        )
    }

    @Test fun `the same core is installed once its folders are there`() {
        val root = root("complete-present")
        deliver(root, "COLECOVISION", "Databases", "Machines")
        assertEquals(
            CoreAvailability.AVAILABLE,
            availability(root, "COLECOVISION", "bluemsx_libretro"),
        )
    }

    // blueMSX unpacks two folders and needs both, so half an extraction is not an install.
    @Test fun `half the folders is still not installed`() {
        val root = root("complete-half")
        deliver(root, "COLECOVISION", "Databases")
        assertEquals(
            CoreAvailability.UNAVAILABLE,
            availability(root, "COLECOVISION", "bluemsx_libretro"),
        )
    }

    @Test fun `ScummVM follows the same rule`() {
        val root = root("complete-scummvm")
        assertEquals(
            CoreAvailability.UNAVAILABLE,
            availability(root, "SCUMMVM", "scummvm_libretro"),
        )
        deliver(root, "SCUMMVM", "scummvm")
        assertEquals(
            CoreAvailability.AVAILABLE,
            availability(root, "SCUMMVM", "scummvm_libretro"),
        )
    }

    // The guard on the whole design: a core with a bundled set must never read as not installed,
    // or the user is sent to download something the APK is already carrying.
    @Test fun `a core with only bundled system files is installed without them on disk`() {
        val root = root("complete-bundled")
        assertEquals(
            CoreAvailability.AVAILABLE,
            availability(root, "PSP", "ppsspp_libretro"),
        )
    }

    @Test fun `a core with no system files at all is unaffected`() {
        val root = root("complete-none")
        assertEquals(
            CoreAvailability.AVAILABLE,
            availability(root, "SNES", "snes9x_libretro"),
        )
    }

    @Test fun `remoteSetsPresent ignores a set belonging to another platform`() {
        val root = root("complete-othertag")
        val bios = File(root, "BIOS/MAME").apply { mkdirs() }
        assertTrue(
            SystemFiles.remoteSetsPresent(ctx.assets, "mame2003_libretro", "MAME", bios)
        )
        assertFalse(
            SystemFiles.remoteSetsPresent(
                ctx.assets, "bluemsx_libretro", "COLECOVISION",
                File(root, "BIOS/COLECOVISION"),
            )
        )
    }
}
