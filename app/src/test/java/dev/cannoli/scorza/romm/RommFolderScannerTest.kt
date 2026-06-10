package dev.cannoli.scorza.romm

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RommFolderScannerTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test fun `lists files with names and sizes for a platform tag`() {
        val roms = tmp.newFolder("Roms")
        val snes = File(roms, "SNES").apply { mkdirs() }
        File(snes, "A.sfc").writeBytes(ByteArray(10))
        File(snes, "B.sfc").writeBytes(ByteArray(20))
        val scanner = RommFolderScanner { roms }
        val files = scanner.localFiles("SNES").associate { it.name to it.sizeBytes }
        assertEquals(10L, files["A.sfc"])
        assertEquals(20L, files["B.sfc"])
    }

    @Test fun `missing platform folder yields empty list`() {
        val roms = tmp.newFolder("Roms")
        val scanner = RommFolderScanner { roms }
        assertEquals(emptyList<LocalFile>(), scanner.localFiles("MISSING"))
    }
}
