package dev.cannoli.scorza.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
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

    @Test fun `language persists to json and mirrors to cannoli_locale`() {
        writeSettingsJson(tmp.root, "{}")
        val settings = newRepo()
        settings.sdCardRoot = tmp.root.absolutePath
        assertEquals("en", settings.language)

        settings.language = "fr-FR"
        assertEquals("fr-FR", settings.language)

        val mirror = ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences("cannoli_locale", Context.MODE_PRIVATE)
            .getString("language", "")
        assertEquals("fr-FR", mirror)
    }

    @Test fun `clearing language falls back to the English default`() {
        writeSettingsJson(tmp.root, """{"language":"fr-FR"}""")
        val settings = newRepo()
        settings.sdCardRoot = tmp.root.absolutePath
        assertEquals("fr-FR", settings.language)

        settings.language = ""
        assertEquals("en", settings.language)
    }

    @Test fun `a repository with no chosen root reports it rather than defaulting`() {
        val settings = newRepo()
        assertNull(settings.sdCardRootOrNull)
        assertFalse(settings.setupCompleted)
    }

    @Test fun `reading the root before the storage step fails loudly`() {
        val settings = newRepo()
        assertThrows(IllegalStateException::class.java) { settings.sdCardRoot }
    }

    @Test fun `choosing a root records it even when settings are never saved`() {
        val settings = newRepo()
        settings.sdCardRoot = tmp.root.absolutePath
        assertEquals(tmp.root.absolutePath, settings.sdCardRootOrNull)
    }

    // Cannoli renders in English until the user chooses otherwise: ProvideLocalizedResources applies
    // this value whether or not the key is present. RetroArch is given the same value, so an unset
    // language must read as English rather than as "no opinion".
    @Test fun `an unset language reads as english`() {
        val settings = newRepo()
        settings.sdCardRoot = tmp.root.absolutePath
        assertEquals("en", settings.language)
    }

    @Test fun `the in-game settings mode defaults to curated`() {
        val settings = newRepo()
        settings.sdCardRoot = tmp.root.absolutePath
        assertEquals(IgmSettingsMode.CURATED, settings.igmSettingsMode)
    }

    @Test fun `the in-game settings mode round-trips`() {
        val settings = newRepo()
        settings.sdCardRoot = tmp.root.absolutePath
        settings.igmSettingsMode = IgmSettingsMode.ALL_SETTINGS
        assertEquals(IgmSettingsMode.ALL_SETTINGS, settings.igmSettingsMode)
    }

    // The mode was called EVERYTHING before it had a name in the UI. Anyone who set it then must
    // not be quietly moved back to the short menu.
    @Test fun `the old spelling of the all-settings mode is still honoured`() {
        writeSettingsJson(tmp.root, """{"igm_settings_mode":"EVERYTHING"}""")
        val settings = newRepo()
        settings.sdCardRoot = tmp.root.absolutePath
        assertEquals(IgmSettingsMode.ALL_SETTINGS, settings.igmSettingsMode)
    }

    // A value written by a newer build, or a hand-edited settings.json, must not leave the IGM
    // with no menu at all.
    @Test fun `an unrecognised in-game settings mode falls back to curated`() {
        writeSettingsJson(tmp.root, """{"igm_settings_mode":"NONSENSE"}""")
        val settings = newRepo()
        settings.sdCardRoot = tmp.root.absolutePath
        assertEquals(IgmSettingsMode.CURATED, settings.igmSettingsMode)
    }
}
