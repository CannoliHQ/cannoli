package dev.cannoli.scorza.launcher

import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BundledCoreCleanupTest {

    private fun context() = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun coresDir(context: android.content.Context) = File(context.filesDir, "cores")

    private fun currentVersion(context: android.content.Context) =
        File(context.applicationInfo.sourceDir).lastModified().toString()

    @Test fun `a core dropped from the current APK and a downloaded-only core both survive a version bump`() {
        val context = context()
        val coresDir = coresDir(context).apply { mkdirs() }
        File(coresDir, "flycast_libretro_android.so").writeText("PRESERVE ME")
        File(coresDir, "mgba_libretro_android.so").writeText("DOWNLOADED, NEVER BUNDLED")
        File(coresDir, ".version").writeText("0")

        LaunchManager.extractBundledCores(context)

        assertEquals("PRESERVE ME", File(coresDir, "flycast_libretro_android.so").readText())
        assertEquals("DOWNLOADED, NEVER BUNDLED", File(coresDir, "mgba_libretro_android.so").readText())
    }

    @Test fun `a version bump updates the stamp to the current APK`() {
        val context = context()
        val coresDir = coresDir(context).apply { mkdirs() }
        File(coresDir, ".version").writeText("0")

        LaunchManager.extractBundledCores(context)

        assertEquals(currentVersion(context), File(coresDir, ".version").readText())
    }

    @Test fun `a matching version skips re-extraction`() {
        val context = context()
        val coresDir = coresDir(context).apply { mkdirs() }
        File(coresDir, "flycast_libretro_android.so").writeText("UNTOUCHED")
        File(coresDir, ".version").writeText(currentVersion(context))

        val result = LaunchManager.extractBundledCores(context)

        assertEquals(coresDir.absolutePath, result)
        assertEquals("UNTOUCHED", File(coresDir, "flycast_libretro_android.so").readText())
    }

    @Test fun `first run creates the cores directory and a version stamp`() {
        val context = context()
        val coresDir = coresDir(context)
        assertTrue(!coresDir.exists())

        LaunchManager.extractBundledCores(context)

        assertEquals(currentVersion(context), File(coresDir, ".version").readText())
    }
}
