package dev.cannoli.scorza.launcher

import dev.cannoli.scorza.download.DownloadStaging
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class CoreWriteAtomicityTest {

    private fun dir(): File = Files.createTempDirectory("cores").toFile()

    private fun staging(d: File) = DownloadStaging { File(d, "cache") }

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

        EmbeddedCoreDownloader.extractEntry(src, "snes9x_libretro_android.so", dest, staging(d))

        assertEquals("new build", dest.readText())
    }

    @Test fun `an extraction that finds nothing leaves the installed core untouched`() {
        val d = dir()
        val dest = File(d, "snes9x_libretro_android.so").apply { writeText("old build") }
        val src = zip(d, "core.zip", "something_else.so" to "irrelevant")

        val error = runCatching {
            EmbeddedCoreDownloader.extractEntry(src, "snes9x_libretro_android.so", dest, staging(d))
        }.exceptionOrNull()

        assertTrue("the failure was swallowed", error is RuntimeException)
        assertEquals("the installed core was damaged", "old build", dest.readText())
    }

    @Test fun `a truncated archive does not leave a half written core`() {
        val d = dir()
        val dest = File(d, "snes9x_libretro_android.so").apply { writeText("old build") }
        val body = "x".repeat(200_000)
        val src = zip(d, "core.zip", "snes9x_libretro_android.so" to body)
        val whole = src.readBytes()
        src.writeBytes(whole.copyOf(whole.size / 2))

        runCatching { EmbeddedCoreDownloader.extractEntry(src, "snes9x_libretro_android.so", dest, staging(d)) }

        assertEquals("the installed core was left half written", "old build", dest.readText())
    }

    @Test fun `an unreadable archive leaves the installed core untouched`() {
        val d = dir()
        val dest = File(d, "snes9x_libretro_android.so").apply { writeText("old build") }
        val src = File(d, "corrupt.zip").apply { writeText("not a zip") }

        runCatching { EmbeddedCoreDownloader.extractEntry(src, "snes9x_libretro_android.so", dest, staging(d)) }

        assertEquals("old build", dest.readText())
    }

    @Test fun `no staging file is left behind, on success or failure`() {
        val d = dir()
        val dest = File(d, "snes9x_libretro_android.so").apply { writeText("old") }

        EmbeddedCoreDownloader.extractEntry(
            zip(d, "ok.zip", "snes9x_libretro_android.so" to "new"),
            "snes9x_libretro_android.so", dest, staging(d),
        )
        runCatching {
            EmbeddedCoreDownloader.extractEntry(
                zip(d, "bad.zip", "other.so" to "x"), "snes9x_libretro_android.so", dest, staging(d),
            )
        }

        val leaked = d.listFiles { f: File -> f.name.endsWith(".part") }.orEmpty().toList() +
            File(d, "cache").listFiles().orEmpty().toList()
        assertTrue("left behind: ${leaked.map { it.name }}", leaked.isEmpty())
    }

    @Test fun `the core stages in the cache and lands alone in the cores folder`() {
        val d = dir()
        val cores = File(d, "cores").apply { mkdirs() }
        val dest = File(cores, "snes9x_libretro_android.so").apply { writeText("old") }

        EmbeddedCoreDownloader.extractEntry(
            zip(d, "core.zip", "snes9x_libretro_android.so" to "new"),
            "snes9x_libretro_android.so", dest, staging(d),
        )

        assertEquals("new", dest.readText())
        assertEquals(listOf("snes9x_libretro_android.so"), cores.list()!!.toList())
        assertTrue(File(d, "cache").list().orEmpty().isEmpty())
    }

    @Test fun `a first install with nothing to replace still works`() {
        val d = dir()
        val dest = File(d, "new_core_libretro_android.so")
        assertFalse(dest.exists())

        EmbeddedCoreDownloader.extractEntry(
            zip(d, "core.zip", "new_core_libretro_android.so" to "fresh"),
            "new_core_libretro_android.so", dest, staging(d),
        )

        assertEquals("fresh", dest.readText())
    }
}
