package dev.cannoli.scorza.updater

import androidx.test.core.app.ApplicationProvider
import dev.cannoli.scorza.settings.SettingsRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UpdateManagerPurgeTest {

    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun manager() = UpdateManager(ctx, SettingsRepository(ctx))

    private fun updatesDir() = File(ctx.cacheDir, "updates").apply { mkdirs() }

    @Test
    fun `deletes downloads left behind by earlier updates`() = runTest {
        val dir = updatesDir()
        val old = File(dir, "cannoli-1.8.0-abc1234.apk").apply { writeText("stale") }
        val older = File(dir, "cannoli-1.7.0-def5678.apk").apply { writeText("staler") }

        manager().purgeStaleDownloads()

        assertFalse(old.exists())
        assertFalse(older.exists())
        assertEquals(0, dir.listFiles()!!.size)
    }

    @Test
    fun `survives a missing updates directory`() = runTest {
        File(ctx.cacheDir, "updates").deleteRecursively()

        manager().purgeStaleDownloads()
    }

    @Test
    fun `leaves other cache directories alone`() = runTest {
        val sibling = File(ctx.cacheDir, "rom_cache").apply { mkdirs() }
        val rom = File(sibling, "game.sfc").apply { writeText("rom") }
        updatesDir()

        manager().purgeStaleDownloads()

        assertTrue(rom.exists())
    }

    private val apk = 299_265_140L

    @Test
    fun `requires the download twice over plus headroom`() {
        assertEquals(apk * 2 + 48L * 1024 * 1024, UpdateManager.requiredFor(apk))
    }

    @Test
    fun `blocks when only the download itself would fit`() {
        assertFalse(UpdateManager.hasRoom(apk, apk + 1024))
    }

    @Test
    fun `allows when both copies and headroom fit`() {
        assertTrue(UpdateManager.hasRoom(apk, UpdateManager.requiredFor(apk)))
    }

    @Test
    fun `blocks one byte short of the requirement`() {
        assertFalse(UpdateManager.hasRoom(apk, UpdateManager.requiredFor(apk) - 1))
    }

    // Chunked responses report no length, so there is nothing to check and the download runs.
    @Test
    fun `allows an unknown content length`() {
        assertTrue(UpdateManager.hasRoom(-1L, 0L))
    }
}
