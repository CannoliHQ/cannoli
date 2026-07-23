package dev.cannoli.scorza.libretro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LockedFramePacerTest {

    private val core60Ns = (1_000_000_000.0 / 60.0).toLong()

    private fun runCount(pacer: LockedFramePacer, deltasNs: List<Long>, frameDurationNs: Long): Int {
        var runs = 0
        for (d in deltasNs) if (pacer.shouldRunFrame(d, frameDurationNs)) runs++
        return runs
    }

    @Test
    fun matched60RunsEveryFrameNoSkips() {
        val pacer = LockedFramePacer()
        val draws = List(600) { core60Ns }
        assertEquals(600, runCount(pacer, draws, core60Ns))
    }

    @Test
    fun matched60WithJitterNeverSkips() {
        val pacer = LockedFramePacer()
        val jitter = longArrayOf(-2_000_000, 2_000_000, -1_500_000, 1_500_000, 0, 3_000_000, -3_000_000)
        val draws = List(600) { core60Ns + jitter[it % jitter.size] }
        assertEquals(600, runCount(pacer, draws, core60Ns))
    }

    @Test
    fun matched60SurvivesResumeHitchWithoutSkipping() {
        val pacer = LockedFramePacer()
        val draws = MutableList(300) { core60Ns }
        draws[150] = 500_000_000L
        assertEquals(300, runCount(pacer, draws, core60Ns))
    }

    @Test
    fun panel90RunsAtCoreRateNot90() {
        val pacer = LockedFramePacer()
        val draw90Ns = (1_000_000_000.0 / 90.0).toLong()
        val draws = List(900) { draw90Ns }
        val runs = runCount(pacer, draws, core60Ns)
        assertTrue("expected ~600 core runs over 10s, got $runs", runs in 590..615)
    }

    @Test
    fun panel120RunsAtCoreRateNot120() {
        val pacer = LockedFramePacer()
        val draw120Ns = (1_000_000_000.0 / 120.0).toLong()
        val draws = List(1200) { draw120Ns }
        val runs = runCount(pacer, draws, core60Ns)
        assertTrue("expected ~600 core runs over 10s, got $runs", runs in 590..615)
    }
}
