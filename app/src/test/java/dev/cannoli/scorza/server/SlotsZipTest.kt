package dev.cannoli.scorza.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipInputStream

class SlotsZipTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun entryNames(zip: ByteArray): List<String> {
        val names = mutableListOf<String>()
        ZipInputStream(ByteArrayInputStream(zip)).use { zis ->
            while (true) {
                val e = zis.nextEntry ?: break
                names.add(e.name)
                zis.closeEntry()
            }
        }
        return names.sorted()
    }

    @Test
    fun `empty directory returns null`() {
        val gameDir = tmp.newFolder("Chrono Trigger")
        assertNull(SlotsZip.build(gameDir, "Chrono Trigger"))
    }

    @Test
    fun `missing directory returns null`() {
        val gameDir = File(tmp.root, "does-not-exist")
        assertNull(SlotsZip.build(gameDir, "does-not-exist"))
    }

    @Test
    fun `includes state files and their thumbnails`() {
        val gameDir = tmp.newFolder("Earthbound")
        File(gameDir, "Earthbound.state").writeBytes(ByteArray(8))
        File(gameDir, "Earthbound.state1").writeBytes(ByteArray(8))
        File(gameDir, "Earthbound.state1.png").writeBytes(ByteArray(4))

        val zip = SlotsZip.build(gameDir, "Earthbound")
        assertTrue(zip != null)
        assertEquals(
            listOf("Earthbound.state", "Earthbound.state1", "Earthbound.state1.png"),
            entryNames(zip!!),
        )
    }

    @Test
    fun `state without thumbnail produces only the state entry`() {
        val gameDir = tmp.newFolder("F-Zero")
        File(gameDir, "F-Zero.state").writeBytes(ByteArray(8))

        val zip = SlotsZip.build(gameDir, "F-Zero")
        assertTrue(zip != null)
        assertEquals(listOf("F-Zero.state"), entryNames(zip!!))
    }
}
