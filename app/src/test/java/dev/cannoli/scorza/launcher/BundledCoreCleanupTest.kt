package dev.cannoli.scorza.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

private const val VERSION = "1754500000000"

class BundledCoreCleanupTest {

    private fun stamp(vararg cores: String) = listOf(VERSION) + cores.toList()

    // The bug this replaced: cleanup deleted anything in the directory that was not bundled, and a
    // downloaded core is by definition not bundled, so every one of them went on the first launch
    // after an app update.
    @Test fun `a downloaded core is never removed`() {
        val stale = LaunchManager.staleBundledCores(
            stamp = stamp("mgba_libretro_android.so"),
            extractedNow = setOf("mgba_libretro_android.so"),
        )
        assertEquals(emptyList<String>(), stale)
    }

    @Test fun `a core dropped from the APK is removed`() {
        val stale = LaunchManager.staleBundledCores(
            stamp = stamp("mgba_libretro_android.so", "flycast_libretro_android.so"),
            extractedNow = setOf("mgba_libretro_android.so"),
        )
        assertEquals(listOf("flycast_libretro_android.so"), stale)
    }

    // A stamp written before the manifest existed is a bare version line. Removing nothing is the
    // safe reading: the manifest is written on the way out, so the next update prunes properly.
    @Test fun `a stamp with no manifest removes nothing`() {
        val stale = LaunchManager.staleBundledCores(
            stamp = listOf(VERSION),
            extractedNow = setOf("mgba_libretro_android.so"),
        )
        assertEquals(emptyList<String>(), stale)
    }

    @Test fun `no stamp at all removes nothing`() {
        val stale = LaunchManager.staleBundledCores(
            stamp = emptyList(),
            extractedNow = setOf("mgba_libretro_android.so"),
        )
        assertEquals(emptyList<String>(), stale)
    }

    @Test fun `a newly bundled core is not treated as stale`() {
        val stale = LaunchManager.staleBundledCores(
            stamp = stamp("mgba_libretro_android.so"),
            extractedNow = setOf("mgba_libretro_android.so", "snes9x_libretro_android.so"),
        )
        assertEquals(emptyList<String>(), stale)
    }
}
