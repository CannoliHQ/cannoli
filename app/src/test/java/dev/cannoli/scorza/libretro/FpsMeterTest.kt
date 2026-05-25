package dev.cannoli.scorza.libretro

import org.junit.Assert.assertEquals
import org.junit.Test

class FpsMeterTest {

    @Test fun `new meter reports zero fps`() {
        val meter = FpsMeter()
        assertEquals(0f, meter.fps, 0f)
        assertEquals(0f, meter.frameTimeMs, 0f)
    }

    @Test fun `single tick does not compute fps yet`() {
        val meter = FpsMeter()
        meter.tick(nowNs = 1_000_000L)
        assertEquals(0f, meter.fps, 0f)
    }

    @Test fun `60 hz cadence converges to 60 fps`() {
        val meter = FpsMeter()
        val frameNs = 16_666_667L
        var t = 0L
        meter.tick(t)
        repeat(1000) {
            t += frameNs
            meter.tick(t)
        }
        assertEquals(60f, meter.fps, 0.1f)
        assertEquals(16.67f, meter.frameTimeMs, 0.05f)
    }

    @Test fun `240 hz cadence converges to 240 fps regardless of host vsync`() {
        val meter = FpsMeter()
        val frameNs = 4_166_667L
        var t = 0L
        meter.tick(t)
        repeat(1000) {
            t += frameNs
            meter.tick(t)
        }
        assertEquals(240f, meter.fps, 0.5f)
    }

    @Test fun `reset clears state so first post-reset tick does not synthesize delta`() {
        val meter = FpsMeter()
        meter.tick(0L)
        meter.tick(16_666_667L)
        val before = meter.fps
        assertEquals(60f, before, 5f)

        meter.reset()
        meter.tick(0L)
        // After reset and a single tick, no delta is yet known: fps should be cleared back to 0.
        assertEquals(0f, meter.fps, 0f)
    }
}
