package dev.cannoli.scorza.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.cannoli.scorza.di.CannoliPathsProvider
import dev.cannoli.scorza.model.AppType
import dev.cannoli.scorza.model.artTag
import dev.cannoli.scorza.settings.SettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ArtworkLookupTest {

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var root: File
    private lateinit var lookup: ArtworkLookup

    @Before fun setUp() {
        root = tmp.newFolder("cannoli")
        val settings = SettingsRepository(ApplicationProvider.getApplicationContext<Context>())
        settings.sdCardRoot = root.absolutePath
        lookup = ArtworkLookup(CannoliPathsProvider(settings))
    }

    private fun writeArt(tag: String, fileName: String): File {
        val dir = File(root, "Art/$tag").apply { mkdirs() }
        return File(dir, fileName).apply { writeBytes(ByteArray(4)) }
    }

    @Test fun `artTag maps tool and port`() {
        assertEquals("TOOLS", AppType.TOOL.artTag)
        assertEquals("PORTS", AppType.PORT.artTag)
    }

    @Test fun `findByName returns the matching file`() {
        val art = writeArt("TOOLS", "Termux.png")
        assertEquals(art, lookup.findByName("TOOLS", "Termux"))
    }

    @Test fun `findByName ignores the extension`() {
        val art = writeArt("PORTS", "Portal 2.webp")
        assertEquals(art, lookup.findByName("PORTS", "Portal 2"))
    }

    @Test fun `findByName returns null when absent`() {
        writeArt("TOOLS", "Termux.png")
        assertNull(lookup.findByName("TOOLS", "Nothing"))
    }

    @Test fun `findByName returns null for a tag with no directory`() {
        assertNull(lookup.findByName("PORTS", "Anything"))
    }

    @Test fun `renameArt moves the file and keeps the extension`() {
        writeArt("TOOLS", "Old Name.jpg")
        assertTrue(lookup.renameArt("TOOLS", "Old Name", "New Name"))
        assertTrue(File(root, "Art/TOOLS/New Name.jpg").exists())
        assertFalse(File(root, "Art/TOOLS/Old Name.jpg").exists())
    }

    @Test fun `renameArt makes the new name findable immediately`() {
        writeArt("TOOLS", "Old Name.jpg")
        lookup.findByName("TOOLS", "Old Name")
        lookup.renameArt("TOOLS", "Old Name", "New Name")
        assertNotNull(lookup.findByName("TOOLS", "New Name"))
        assertNull(lookup.findByName("TOOLS", "Old Name"))
    }

    @Test fun `renameArt returns false when there is no art`() {
        assertFalse(lookup.renameArt("TOOLS", "Missing", "Whatever"))
    }

    @Test fun `renameArt refuses to overwrite existing art at the new name`() {
        writeArt("TOOLS", "Alpha.png")
        writeArt("TOOLS", "Beta.jpg")
        assertFalse(lookup.renameArt("TOOLS", "Alpha", "Beta"))
        assertTrue(File(root, "Art/TOOLS/Alpha.png").exists())
        assertTrue(File(root, "Art/TOOLS/Beta.jpg").exists())
    }

    @Test fun `deleteArt removes the file and clears the cache`() {
        writeArt("PORTS", "Doom.png")
        lookup.findByName("PORTS", "Doom")
        assertTrue(lookup.deleteArt("PORTS", "Doom"))
        assertFalse(File(root, "Art/PORTS/Doom.png").exists())
        assertNull(lookup.findByName("PORTS", "Doom"))
    }

    @Test fun `deleteArt returns false when there is no art`() {
        assertFalse(lookup.deleteArt("PORTS", "Missing"))
    }
}
