package dev.cannoli.scorza.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ArtThumbnailsTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun sourceArt(name: String = "cover.png"): File {
        val file = File(tmp.newFolder("art"), name)
        val bitmap = android.graphics.Bitmap.createBitmap(200, 300, android.graphics.Bitmap.Config.ARGB_8888)
        val bytes = ByteArrayOutputStream()
        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, bytes)
        file.writeBytes(bytes.toByteArray())
        return file
    }

    @Test fun `width outside the accepted range is refused`() {
        val thumbs = ArtThumbnails(tmp.newFolder("cache"))
        val art = sourceArt()
        assertNull(thumbs.thumbnail(art, ArtThumbnails.MIN_WIDTH - 1))
        assertNull(thumbs.thumbnail(art, ArtThumbnails.MAX_WIDTH + 1))
    }

    @Test fun `a source narrower than the target is left alone`() {
        val thumbs = ArtThumbnails(tmp.newFolder("cache"))
        assertNull(thumbs.thumbnail(sourceArt(), ArtThumbnails.MAX_WIDTH))
    }

    @Test fun `the second request reuses the cached file`() {
        val thumbs = ArtThumbnails(tmp.newFolder("cache"))
        val art = sourceArt()
        val first = thumbs.thumbnail(art, 64)
        assertNotNull(first)
        val stamp = first!!.lastModified()
        val second = thumbs.thumbnail(art, 64)
        assertEquals(first, second)
        assertEquals(stamp, second!!.lastModified())
    }

    @Test fun `replacing the art file invalidates the cached thumbnail`() {
        val cache = tmp.newFolder("cache")
        val thumbs = ArtThumbnails(cache)
        val art = sourceArt()
        val first = thumbs.thumbnail(art, 64)
        assertNotNull(first)

        art.writeBytes(art.readBytes() + ByteArray(16))
        art.setLastModified(art.lastModified() + 5_000)

        val second = thumbs.thumbnail(art, 64)
        assertNotNull(second)
        assertNotEquals(first!!.name, second!!.name)
    }

    @Test fun `each width is cached separately`() {
        val thumbs = ArtThumbnails(tmp.newFolder("cache"))
        val art = sourceArt()
        val small = thumbs.thumbnail(art, 64)
        val large = thumbs.thumbnail(art, 128)
        assertNotNull(small)
        assertNotNull(large)
        assertNotEquals(small!!.name, large!!.name)
    }

    @Test fun `no temp files are left behind`() {
        val cache = tmp.newFolder("cache")
        val thumbs = ArtThumbnails(cache)
        thumbs.thumbnail(sourceArt(), 64)
        val leftovers = File(cache, ".").listFiles()?.filter { it.name.endsWith(".tmp") } ?: emptyList()
        assertTrue(leftovers.toString(), leftovers.isEmpty())
    }
}
