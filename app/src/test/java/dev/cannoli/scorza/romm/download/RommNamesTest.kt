package dev.cannoli.scorza.romm.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RommNamesTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test fun `linked game keys guides on the installed file, not the RomM title`() {
        assertEquals(
            "Chrono Trigger (USA)",
            guideBaseName("SNES/Chrono Trigger (USA).sfc", "Chrono Trigger (USA).sfc"),
        )
    }

    @Test fun `linked multi-disc game keys guides on the m3u the launcher launches`() {
        assertEquals(
            "FF7",
            guideBaseName("PSX/Final Fantasy VII/FF7.m3u", "Final Fantasy VII (USA)"),
        )
    }

    @Test fun `unlinked game keys guides on the name the rom will have once downloaded`() {
        assertEquals("Chrono Trigger (USA)", guideBaseName(null, "Chrono Trigger (USA).sfc"))
    }

    @Test fun `base name strips characters exfat rejects`() {
        assertEquals(
            "Zelda_ A Link to the Past",
            guideBaseName(null, "Zelda: A Link to the Past.sfc"),
        )
    }

    @Test fun `base name does not let a separator nest a directory`() {
        assertFalse('/' in guideBaseName(null, "Sonic 3 / Knuckles.md"))
    }

    @Test fun `adopt moves a pre-download manual onto the installed base name`() {
        val guides = tmp.newFolder("Guides", "PSX")
        File(guides, "Final Fantasy VII (USA)").mkdirs()
        File(guides, "Final Fantasy VII (USA)/Manual.pdf").writeText("pdf")

        adoptGuideDir(guides, "Final Fantasy VII (USA)", "FF7")

        assertEquals("pdf", File(guides, "FF7/Manual.pdf").readText())
        assertFalse(File(guides, "Final Fantasy VII (USA)").exists())
    }

    @Test fun `adopt merges into a guide folder that already exists`() {
        val guides = tmp.newFolder("Guides", "PSX")
        File(guides, "Final Fantasy VII (USA)").mkdirs()
        File(guides, "Final Fantasy VII (USA)/Manual.pdf").writeText("pdf")
        File(guides, "FF7").mkdirs()
        File(guides, "FF7/Notes.txt").writeText("notes")

        adoptGuideDir(guides, "Final Fantasy VII (USA)", "FF7")

        assertEquals("pdf", File(guides, "FF7/Manual.pdf").readText())
        assertEquals("notes", File(guides, "FF7/Notes.txt").readText())
        assertFalse(File(guides, "Final Fantasy VII (USA)").exists())
    }

    @Test fun `adopt leaves guides alone when the base name did not change`() {
        val guides = tmp.newFolder("Guides", "SNES")
        File(guides, "Chrono Trigger (USA)").mkdirs()
        File(guides, "Chrono Trigger (USA)/Manual.pdf").writeText("pdf")

        adoptGuideDir(guides, "Chrono Trigger (USA)", "Chrono Trigger (USA)")

        assertTrue(File(guides, "Chrono Trigger (USA)/Manual.pdf").isFile)
    }

    @Test fun `adopt is a no-op when no manual was downloaded ahead of the rom`() {
        val guides = tmp.newFolder("Guides", "PSX")

        adoptGuideDir(guides, "Final Fantasy VII (USA)", "FF7")

        assertFalse(File(guides, "FF7").exists())
    }
}
