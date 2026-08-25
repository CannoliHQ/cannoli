package dev.cannoli.scorza.util

import androidx.test.core.app.ApplicationProvider
import dev.cannoli.scorza.config.CoreInfoRepository
import dev.cannoli.scorza.config.PlatformConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Box art lives on shared storage, so without a marker every cover Cannoli downloads turns up in
 * the user's gallery beside their photos.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ArtNoMediaTest {

    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun seeded(name: String): File {
        val root = File(ctx.cacheDir, name).apply { deleteRecursively(); mkdirs() }
        val coreInfo = CoreInfoRepository(ctx.assets).also { it.load() }
        val config = PlatformConfig(root, ctx.assets, coreInfo)
        DirectoryLayout.ensure(root, File(root, "Roms"), ctx.assets, config, ctx)
        return root
    }

    @Test fun `the Art root is hidden from the gallery`() {
        assertTrue(File(seeded("nomedia-root"), "Art/.nomedia").isFile)
    }

    @Test fun `every platform folder under Art is hidden too`() {
        val art = File(seeded("nomedia-platforms"), "Art")
        val dirs = art.listFiles { f: File -> f.isDirectory }.orEmpty()
        assertTrue("no platform folders were seeded", dirs.isNotEmpty())
        val bare = dirs.filter { !File(it, ".nomedia").isFile }.map { it.name }
        assertTrue("not hidden: $bare", bare.isEmpty())
    }

    // Nothing else on the card should gain one: these hold files the user put there on purpose.
    @Test fun `only Art is hidden`() {
        val root = seeded("nomedia-scope")
        for (dir in listOf("Roms", "Saves", "Save States", "BIOS", "Guides", "Wallpapers", "Media")) {
            assertFalse("$dir was hidden", File(root, "$dir/.nomedia").exists())
        }
    }

    @Test fun `seeding twice leaves one marker and does not throw`() {
        val root = seeded("nomedia-twice")
        val marker = File(root, "Art/.nomedia")
        marker.writeText("")
        val coreInfo = CoreInfoRepository(ctx.assets).also { it.load() }
        DirectoryLayout.ensure(
            root, File(root, "Roms"), ctx.assets, PlatformConfig(root, ctx.assets, coreInfo)
        )
        assertTrue(marker.isFile)
    }

    /**
     * The marker only stops the next scan. A library scraped before it existed is already in
     * MediaStore, so the rescan is what actually clears the gallery, and it must run once rather
     * than on every boot: it walks the whole art tree.
     */
    @Test fun `the rescan is marked so it runs once, not every boot`() {
        val root = seeded("nomedia-rescan")
        val marker = File(root, "Art/.rescanned_for_nomedia")
        assertTrue("the rescan never ran", marker.isFile)
        val stamp = marker.lastModified()

        val coreInfo = CoreInfoRepository(ctx.assets).also { it.load() }
        DirectoryLayout.ensure(
            root, File(root, "Roms"), ctx.assets,
            PlatformConfig(root, ctx.assets, coreInfo), ctx,
        )
        assertEquals("the rescan ran again", stamp, marker.lastModified())
    }

    // Nothing should be scanned when no context is supplied, which is how the tests above run.
    @Test fun `seeding without a context leaves no rescan marker`() {
        val root = File(ctx.cacheDir, "nomedia-nocontext").apply { deleteRecursively(); mkdirs() }
        val coreInfo = CoreInfoRepository(ctx.assets).also { it.load() }
        DirectoryLayout.ensure(
            root, File(root, "Roms"), ctx.assets, PlatformConfig(root, ctx.assets, coreInfo)
        )
        assertTrue(File(root, "Art/.nomedia").isFile)
        assertFalse(File(root, "Art/.rescanned_for_nomedia").exists())
    }

    // A folder can outlive the marker above it, so the root alone is not enough.
    @Test fun `a platform folder created later is hidden on its own`() {
        val root = File(ctx.cacheDir, "nomedia-later").apply { deleteRecursively(); mkdirs() }
        val late = File(root, "Art/SNES")
        DirectoryLayout.hideFromGallery(late)
        assertTrue(File(late, ".nomedia").isFile)
    }
}
