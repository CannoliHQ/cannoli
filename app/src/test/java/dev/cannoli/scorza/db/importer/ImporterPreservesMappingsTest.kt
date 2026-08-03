package dev.cannoli.scorza.db.importer

import androidx.test.core.app.ApplicationProvider
import dev.cannoli.scorza.config.PlatformConfig
import dev.cannoli.scorza.db.CannoliDatabase
import dev.cannoli.scorza.db.RomScanner
import dev.cannoli.scorza.db.ScanScheduler
import dev.cannoli.scorza.di.CannoliPathsProvider
import dev.cannoli.scorza.util.ArcadeTitleLookup
import dev.cannoli.scorza.util.ArtworkLookup
import dev.cannoli.scorza.util.RomDirectoryWalker
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
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
class ImporterPreservesMappingsTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test fun `a platform mapping survives an import and a fresh load`() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val root = tmp.root
        val romsDir = File(root, "Roms").also { it.mkdirs() }
        File(root, "Config").apply { mkdirs() }
            .also { File(it, "cores.json").writeText("""{"cores":{"NES":"fceumm_libretro"}}""") }

        val paths = mockk<CannoliPathsProvider>()
        every { paths.root } returns root
        every { paths.romDir } returns romsDir

        val platformConfig = PlatformConfig(root, ctx.assets).also { it.load() }
        val db = CannoliDatabase(paths)
        val walker = RomDirectoryWalker(paths, ctx.assets, ArcadeTitleLookup(paths))
        val scheduler = ScanScheduler(RomScanner(db, walker, ArtworkLookup(paths)), platformConfig)

        val result = Importer(
            context = ctx,
            cannoliRoot = root,
            romDirectory = romsDir,
            db = db,
            platformConfig = platformConfig,
            scanScheduler = scheduler,
            onProgress = ImportProgress { _, _ -> },
            scanDisk = false,
        ).run()

        assertTrue("the import must actually run, got $result", result is ImportResult.Success)
        assertTrue("cores.json must still exist after import", File(root, "Config/cores.json").exists())

        val reloaded = PlatformConfig(root, ctx.assets).also { it.load() }
        assertEquals("fceumm_libretro", reloaded.getCoreMapping("NES"))
    }
}
