package dev.cannoli.scorza.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
class SettingsRepositoryTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun newRepo() = SettingsRepository(ApplicationProvider.getApplicationContext<Context>())

    private fun writeSettingsJson(root: File, json: String) {
        File(root, "Config").apply { mkdirs() }
        File(root, "Config/settings.json").writeText(json)
    }

    @Test fun `reload keeps un-flushed in-memory edits instead of reverting to disk`() {
        writeSettingsJson(tmp.root, """{"font":"old"}""")
        val settings = newRepo()
        settings.sdCardRoot = tmp.root.absolutePath
        assertEquals("old", settings.font)

        // Edit with the debounced save still pending; disk continues to hold "old".
        // reload() must not let loadFromDisk clobber the newer in-memory value.
        settings.font = "new"
        settings.reload()

        assertEquals("new", settings.font)
    }

    @Test fun `reload picks up external disk changes when nothing is pending`() {
        writeSettingsJson(tmp.root, """{"font":"old"}""")
        val settings = newRepo()
        settings.sdCardRoot = tmp.root.absolutePath
        assertEquals("old", settings.font)

        // No pending local edit: a clean reload should still surface a file an
        // external writer (e.g. the Kitchen web UI) changed underneath us.
        writeSettingsJson(tmp.root, """{"font":"external"}""")
        settings.reload()

        assertEquals("external", settings.font)
    }

    @Test fun `dual-screen launching defaults off and can be enabled`() {
        val settings = newRepo()

        assertFalse(settings.dualScreenLaunching)
        assertFalse(settings.topScreenBlackout)
        assertFalse(settings.dimLauncherDuringGames)
        settings.dualScreenLaunching = true
        settings.topScreenBlackout = true
        settings.dimLauncherDuringGames = true
        assertTrue(settings.dualScreenLaunching)
        assertTrue(settings.topScreenBlackout)
        assertTrue(settings.dimLauncherDuringGames)
    }
}
