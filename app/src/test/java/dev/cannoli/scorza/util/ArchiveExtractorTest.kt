package dev.cannoli.scorza.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ArchiveExtractorTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun makeZip(zipName: String, innerName: String, bytes: ByteArray): File {
        val zip = File(tmp.root, zipName)
        ZipOutputStream(zip.outputStream()).use { out ->
            out.putNextEntry(ZipEntry(innerName))
            out.write(bytes)
            out.closeEntry()
        }
        return zip
    }

    @Test fun `primaryEntryName returns inner basename`() {
        val zip = makeZip("Outer.zip", "Inner Name (USA).sfc", ByteArray(64) { 1 })
        assertEquals("Inner Name (USA).sfc", ArchiveExtractor.primaryEntryName(zip))
    }

    @Test fun `primaryEntryName strips path components`() {
        val zip = makeZip("Outer.zip", "subdir/rom.sfc", ByteArray(16) { 1 })
        assertEquals("rom.sfc", ArchiveExtractor.primaryEntryName(zip))
    }

    @Test fun `extract names output by desiredBaseName keeping inner extension`() {
        val zip = makeZip("Outer.zip", "Inner Name (USA).sfc", ByteArray(64) { 1 })
        val cache = tmp.newFolder("cache")
        val out = ArchiveExtractor.extract(zip, cache, "Outer")
        assertTrue(out != null)
        assertEquals("Outer.sfc", out!!.name)
        assertEquals(64L, out.length())
    }
}
