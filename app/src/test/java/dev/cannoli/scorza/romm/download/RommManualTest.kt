package dev.cannoli.scorza.romm.download

import dev.cannoli.scorza.romm.RommGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RommManualTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun game(
        hasManual: Boolean = false,
        manualPath: String? = null,
    ) = RommGame(
        id = 1,
        platformId = 2,
        name = "Chrono Trigger",
        fsName = "Chrono Trigger (USA).sfc",
        sizeBytes = 0,
        summary = null,
        revision = null,
        regions = emptyList(),
        languages = emptyList(),
        coverPath = null,
        files = emptyList(),
        hasManual = hasManual,
        manualPath = manualPath,
    )

    @Test fun `downloads the copy RomM already fetched, under the resources mount`() {
        // path_manual comes over the wire relative to the resources base; the resolver adds the mount.
        val source = RommManual.sourceUrl(
            "https://romm.local",
            game(hasManual = true, manualPath = "roms/12/1/manual/1.pdf"),
        )
        assertEquals("https://romm.local/assets/romm/resources/roms/12/1/manual/1.pdf", source)
    }

    @Test fun `ignores a stale manual path when RomM says it has no manual`() {
        assertNull(
            RommManual.sourceUrl("https://romm.local", game(hasManual = false, manualPath = "assets/manual/1.pdf")),
        )
    }

    @Test fun `no source when RomM has no copy`() {
        assertNull(RommManual.sourceUrl("https://romm.local", game()))
    }

    @Test fun `a game is manual-capable only from the RomM stored copy`() {
        assertTrue(RommManual.isAvailable(game(hasManual = true, manualPath = "assets/manual/1.pdf")))
        assertFalse(RommManual.isAvailable(game(hasManual = true, manualPath = null)))
        assertFalse(RommManual.isAvailable(game()))
    }

    @Test fun `accepts a real pdf`() {
        val file = tmp.newFile("good.pdf")
        file.writeBytes("%PDF-1.4\n%âãÏÓ\n1 0 obj".toByteArray())
        assertTrue(RommManual.looksLikePdf(file))
    }

    @Test fun `rejects an html error page served as a manual`() {
        val file = tmp.newFile("bad.pdf")
        file.writeText("<!DOCTYPE html><html><body>Erreur : quota depasse</body></html>")
        assertFalse(RommManual.looksLikePdf(file))
    }

    @Test fun `rejects a plain text error body served with a 200`() {
        val file = tmp.newFile("err.pdf")
        file.writeText("Erreur : Le media demande n'existe pas")
        assertFalse(RommManual.looksLikePdf(file))
    }

    @Test fun `rejects an empty body`() {
        assertFalse(RommManual.looksLikePdf(tmp.newFile("empty.pdf")))
    }

    @Test fun `rejects a markdown manual, which the guide viewer cannot render`() {
        val file = tmp.newFile("manual.pdf")
        file.writeText("# Chrono Trigger\n\nInsert cartridge.")
        assertFalse(RommManual.looksLikePdf(file))
    }

    @Test fun `tolerates leading bytes before the pdf header`() {
        val file = tmp.newFile("padded.pdf")
        file.writeBytes("\n\n   %PDF-1.7\ntrailer".toByteArray())
        assertTrue(RommManual.looksLikePdf(file))
    }

    @Test fun `does not scan an unbounded distance for the header`() {
        val file = tmp.newFile("late.pdf")
        file.writeBytes(ByteArray(4096) { ' '.code.toByte() } + "%PDF-1.4".toByteArray())
        assertFalse(RommManual.looksLikePdf(file))
    }

    @Test fun `missing file is not a pdf`() {
        assertFalse(RommManual.looksLikePdf(File(tmp.root, "nope.pdf")))
    }
}
