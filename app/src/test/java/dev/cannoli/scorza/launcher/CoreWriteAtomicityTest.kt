package dev.cannoli.scorza.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * A core is the file RetroArch will `dlopen`. Writing a download straight over it means an
 * interrupted extraction leaves a truncated binary that still looks like a core, and the launcher
 * loads it. Staging beside the destination and renaming makes the swap atomic, so the file on disk
 * is the old build or the new one and never half of either.
 *
 * That guarantee is what lets a cancelled update leave completed cores in place rather than having
 * to roll them back.
 */
class CoreWriteAtomicityTest {

    private fun dir(): File = Files.createTempDirectory("cores").toFile()

    private fun zip(dir: File, name: String, vararg entries: Pair<String, String>): File =
        File(dir, name).apply {
            ZipOutputStream(outputStream()).use { zos ->
                entries.forEach { (entry, body) ->
                    zos.putNextEntry(ZipEntry(entry))
                    zos.write(body.toByteArray())
                    zos.closeEntry()
                }
            }
        }

    @Test fun `a good extraction replaces the core`() {
        val d = dir()
        val dest = File(d, "snes9x_libretro_android.so").apply { writeText("old build") }
        val src = zip(d, "core.zip", "snes9x_libretro_android.so" to "new build")

        EmbeddedCoreDownloader.extractEntry(src, "snes9x_libretro_android.so", dest)

        assertEquals("new build", dest.readText())
    }

    // The reason the whole thing exists: a failure must not damage what is already installed.
    @Test fun `an extraction that finds nothing leaves the installed core untouched`() {
        val d = dir()
        val dest = File(d, "snes9x_libretro_android.so").apply { writeText("old build") }
        val src = zip(d, "core.zip", "something_else.so" to "irrelevant")

        val error = runCatching {
            EmbeddedCoreDownloader.extractEntry(src, "snes9x_libretro_android.so", dest)
        }.exceptionOrNull()

        assertTrue("the failure was swallowed", error is RuntimeException)
        assertEquals("the installed core was damaged", "old build", dest.readText())
    }

    /**
     * The case the staging exists for. The other failures here happen before a byte is written, so
     * they pass either way; this one starts writing and then dies, which is what an interrupted
     * download or a cancel actually looks like.
     */
    @Test fun `a truncated archive does not leave a half written core`() {
        val d = dir()
        val dest = File(d, "snes9x_libretro_android.so").apply { writeText("old build") }
        val body = "x".repeat(200_000)
        val src = zip(d, "core.zip", "snes9x_libretro_android.so" to body)
        // Cut the archive mid-entry: the header still parses, so extraction begins and then hits
        // the end of the stream partway through the payload.
        val whole = src.readBytes()
        src.writeBytes(whole.copyOf(whole.size / 2))

        runCatching { EmbeddedCoreDownloader.extractEntry(src, "snes9x_libretro_android.so", dest) }

        assertEquals("the installed core was left half written", "old build", dest.readText())
    }

    @Test fun `an unreadable archive leaves the installed core untouched`() {
        val d = dir()
        val dest = File(d, "snes9x_libretro_android.so").apply { writeText("old build") }
        val src = File(d, "corrupt.zip").apply { writeText("not a zip") }

        runCatching { EmbeddedCoreDownloader.extractEntry(src, "snes9x_libretro_android.so", dest) }

        assertEquals("old build", dest.readText())
    }

    // A leaked stage would be loaded by nothing but would sit in the cores directory forever, and
    // the listing that decides which cores are installed reads that directory.
    @Test fun `no staging file is left behind, on success or failure`() {
        val d = dir()
        val dest = File(d, "snes9x_libretro_android.so").apply { writeText("old") }

        EmbeddedCoreDownloader.extractEntry(
            zip(d, "ok.zip", "snes9x_libretro_android.so" to "new"),
            "snes9x_libretro_android.so", dest,
        )
        runCatching {
            EmbeddedCoreDownloader.extractEntry(
                zip(d, "bad.zip", "other.so" to "x"), "snes9x_libretro_android.so", dest,
            )
        }

        val leaked = d.listFiles { f: File -> f.name.endsWith(".part") }.orEmpty()
        assertTrue("left behind: ${leaked.map { it.name }}", leaked.isEmpty())
    }

    @Test fun `a first install with nothing to replace still works`() {
        val d = dir()
        val dest = File(d, "new_core_libretro_android.so")
        assertFalse(dest.exists())

        EmbeddedCoreDownloader.extractEntry(
            zip(d, "core.zip", "new_core_libretro_android.so" to "fresh"),
            "new_core_libretro_android.so", dest,
        )

        assertEquals("fresh", dest.readText())
    }
}
