package dev.cannoli.scorza.sigil

import dev.cannoli.scorza.db.GameIdRepository
import dev.cannoli.scorza.db.ProbeTarget
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SigilBackfillEngineTest {

    @get:Rule val tmp = TemporaryFolder()

    private val repository: GameIdRepository = mockk(relaxed = true)

    private fun found(titleId: String) = SigilNative.Result.Extracted(
        GameId(titleId, titleId, titleId, SaveUsage.FOLDER_EXACT, IdSource.BINARY, false)
    )

    private fun engine(
        files: (File) -> List<File> = { listOf(it) },
        extract: (String, SigilPlatform) -> SigilNative.Result = { _, _ -> found("SLUS-20946") },
    ) = SigilBackfillEngine(repository, files, extract)

    /** The walker makes a cue the launch file for a bin/cue game, and sigil cannot read a cue. */
    @Test fun `probes the image beside a cue rather than the cue itself`() {
        val dir = tmp.newFolder("Game")
        val cue = File(dir, "Game.cue").apply { writeText("FILE \"Game.bin\" BINARY") }
        val bin = File(dir, "Game.bin").apply { writeBytes(ByteArray(2048)) }
        val probed = mutableListOf<String>()

        engine(files = { listOf(cue, bin) }, extract = { path, _ -> probed.add(path); found("SLUS-1") })
            .probeOne(ProbeTarget(1L, cue, "PS"))

        assertEquals(listOf(bin.absolutePath), probed)
    }

    /** A multi-disc set launches from an m3u, and disc one is what boots. */
    @Test fun `probes disc one of a multi disc set`() {
        val dir = tmp.newFolder("Set")
        val m3u = File(dir, "Set.m3u").apply { writeText("d1.bin\nd2.bin\n") }
        val d1 = File(dir, "d1.bin").apply { writeBytes(ByteArray(16)) }
        val d2 = File(dir, "d2.bin").apply { writeBytes(ByteArray(16)) }
        val probed = mutableListOf<String>()

        engine(files = { listOf(m3u, d1, d2) }, extract = { path, _ -> probed.add(path); found("SLES-1") })
            .probeOne(ProbeTarget(1L, m3u, "PS"))

        assertEquals(listOf(d1.absolutePath), probed)
    }

    @Test fun `a failure is still recorded so it is never retried`() {
        val iso = File(tmp.newFolder("PS2"), "Game.iso").apply { writeBytes(ByteArray(9)) }
        val probe = slot<String>()

        engine(extract = { _, _ -> SigilNative.Result.Failed("not found") })
            .probeOne(ProbeTarget(7L, iso, "PS2"))

        verify { repository.record(7L, capture(probe), null) }
        assertEquals("9:${iso.lastModified()}", probe.captured)
    }

    /** Nothing was read, so marking the library probed would lock a later build out of it. */
    @Test fun `an unavailable library records nothing`() {
        val iso = File(tmp.newFolder("PS2"), "Game.iso").apply { writeBytes(ByteArray(4)) }

        engine(extract = { _, _ -> SigilNative.Result.Unavailable }).probeOne(ProbeTarget(3L, iso, "PS2"))

        verify(exactly = 0) { repository.record(any(), any(), any()) }
    }

    /**
     * Recording nothing means the next pending() hands the same row straight back, so a drain that
     * kept going would ask the same question until the launcher was backgrounded, at full tilt.
     */
    @Test fun `an unavailable library stops the drain instead of spinning on it`() = runTest {
        val dir = tmp.newFolder("PS2")
        val targets = (1L..3L).map {
            ProbeTarget(it, File(dir, "g$it.iso").apply { writeBytes(ByteArray(2)) }, "PS2")
        }
        // Always the same unprobed batch, exactly as the real query would answer.
        every { repository.pending(any(), any()) } returns targets
        var calls = 0

        val probed = engine(extract = { _, _ -> calls++; SigilNative.Result.Unavailable }).drain(batchSize = 8)

        assertEquals(0, probed)
        assertEquals(1, calls)
    }

    @Test fun `an extractor that throws does not stop the row being recorded`() {
        val iso = File(tmp.newFolder("PS2"), "Game.iso").apply { writeBytes(ByteArray(1)) }

        engine(extract = { _, _ -> throw IllegalStateException("boom") }).probeOne(ProbeTarget(4L, iso, "PS2"))

        verify { repository.record(4L, any(), null) }
    }

    @Test fun `an unmapped tag is recorded without reading the file`() {
        val rom = File(tmp.newFolder("SNES"), "Game.sfc").apply { writeBytes(ByteArray(1)) }
        var called = false

        engine(extract = { _, _ -> called = true; found("X") }).probeOne(ProbeTarget(5L, rom, "SNES"))

        assertTrue(!called)
        verify { repository.record(5L, any(), null) }
    }

    @Test fun `the drain works through every pending row and then stops`() = runTest {
        val dir = tmp.newFolder("PS2")
        val targets = (1L..3L).map {
            ProbeTarget(it, File(dir, "g$it.iso").apply { writeBytes(ByteArray(2)) }, "PS2")
        }
        every { repository.pending(any(), any()) } returnsMany listOf(targets, emptyList())

        val probed = engine().drain(batchSize = 8)

        assertEquals(3, probed)
        verify(exactly = 3) { repository.record(any(), any(), any()) }
    }

    /** A game starting means the pass stops where it is; the rows it never reached stay pending. */
    @Test fun `the drain gives up as soon as the launcher goes away`() = runTest {
        val dir = tmp.newFolder("PS2")
        val targets = (1L..4L).map {
            ProbeTarget(it, File(dir, "g$it.iso").apply { writeBytes(ByteArray(2)) }, "PS2")
        }
        every { repository.pending(any(), any()) } returns targets
        var seen = 0

        val probed = engine(extract = { _, _ -> seen++; found("SLUS-2") })
            .drain(batchSize = 8, isActive = { seen < 2 })

        assertEquals(2, probed)
    }
}
