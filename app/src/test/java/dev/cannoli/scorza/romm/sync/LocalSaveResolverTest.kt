package dev.cannoli.scorza.romm.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipFile

class LocalSaveResolverTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun saves(tag: String) = File(tmp.root, "Saves/$tag").apply { mkdirs() }

    @Test fun resolve_null_when_no_save() {
        assertNull(LocalSaveResolver(tmp.root).resolve("SNES", "Mario"))
    }

    @Test fun resolve_single_srm_is_not_bundle() {
        File(saves("SNES"), "Mario.srm").writeBytes("SRAM".toByteArray())
        val s = LocalSaveResolver(tmp.root).resolve("SNES", "Mario")!!
        assertFalse(s.isBundle)
        assertEquals("Mario.srm", s.uploadFileName)
        assertEquals(SaveHasher.hashFile(File(saves("SNES"), "Mario.srm")), s.contentHash)
    }

    @Test fun resolve_multifile_is_bundle_with_zip_hash() {
        File(saves("GBA"), "Pokemon.srm").writeBytes("SAVE".toByteArray())
        File(saves("GBA"), "Pokemon.rtc").writeBytes("RTC".toByteArray())
        val s = LocalSaveResolver(tmp.root).resolve("GBA", "Pokemon")!!
        assertTrue(s.isBundle)
        assertEquals("Pokemon.zip", s.uploadFileName)
        val expected = SaveHasher.hashBundle(mapOf(
            "Pokemon.rtc" to File(saves("GBA"), "Pokemon.rtc"),
            "Pokemon.srm" to File(saves("GBA"), "Pokemon.srm"),
        ))
        assertEquals(expected, s.contentHash)
    }

    @Test fun bundleToZip_round_trips_via_applyDownload() {
        File(saves("GBA"), "Pokemon.srm").writeBytes("SAVE".toByteArray())
        File(saves("GBA"), "Pokemon.rtc").writeBytes("RTC".toByteArray())
        val resolver = LocalSaveResolver(tmp.root)
        val zip = resolver.bundleToZip("GBA", "Pokemon", tmp.newFile("Pokemon.zip"))
        ZipFile(zip).use { assertEquals(setOf("Pokemon.rtc", "Pokemon.srm"), it.entries().toList().map { e -> e.name }.toSet()) }
        // wipe and re-extract
        File(saves("GBA"), "Pokemon.srm").delete()
        File(saves("GBA"), "Pokemon.rtc").delete()
        resolver.applyDownload("GBA", "Pokemon", zip)
        assertEquals("SAVE", File(saves("GBA"), "Pokemon.srm").readText())
        assertEquals("RTC", File(saves("GBA"), "Pokemon.rtc").readText())
    }
}
