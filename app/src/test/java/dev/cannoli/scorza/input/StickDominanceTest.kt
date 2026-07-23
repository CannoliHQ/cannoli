package dev.cannoli.scorza.input

import org.junit.Assert.assertEquals
import org.junit.Test

class StickDominanceTest {

    @Test fun `vertical wins when it has the larger magnitude`() {
        val s = StickDominance()
        assertEquals(DominantAxis.VERTICAL, s.dominantAxis(1, -0.55f, -0.95f))
    }

    @Test fun `horizontal wins when it has the larger magnitude`() {
        val s = StickDominance()
        assertEquals(DominantAxis.HORIZONTAL, s.dominantAxis(1, -0.95f, -0.55f))
    }

    @Test fun `a vector below the press threshold latches nothing`() {
        val s = StickDominance()
        assertEquals(DominantAxis.NONE, s.dominantAxis(1, 0.4f, 0.45f))
        // Nothing latched, so the next above-threshold vector is free to pick by magnitude.
        assertEquals(DominantAxis.VERTICAL, s.dominantAxis(1, 0.6f, 0.9f))
    }

    @Test fun `the latched axis holds through 45 degrees without flicker`() {
        val s = StickDominance()
        assertEquals(DominantAxis.HORIZONTAL, s.dominantAxis(1, -0.9f, -0.2f))
        // Sweep the vector past x == y. Horizontal must hold the whole way while |x| >= 0.3.
        assertEquals(DominantAxis.HORIZONTAL, s.dominantAxis(1, -0.8f, -0.6f))
        assertEquals(DominantAxis.HORIZONTAL, s.dominantAxis(1, -0.7f, -0.7f))
        assertEquals(DominantAxis.HORIZONTAL, s.dominantAxis(1, -0.6f, -0.8f))
        assertEquals(DominantAxis.HORIZONTAL, s.dominantAxis(1, -0.4f, -0.9f))
    }

    @Test fun `the other axis takes over once the latched axis drops below release`() {
        val s = StickDominance()
        s.dominantAxis(1, -0.9f, -0.2f)
        assertEquals(DominantAxis.VERTICAL, s.dominantAxis(1, -0.2f, -0.95f))
    }

    @Test fun `releasing to neutral clears the latch`() {
        val s = StickDominance()
        s.dominantAxis(1, -0.9f, -0.2f)
        assertEquals(DominantAxis.NONE, s.dominantAxis(1, 0f, 0f))
        assertEquals(DominantAxis.VERTICAL, s.dominantAxis(1, -0.5f, -0.9f))
    }

    @Test fun `devices latch independently`() {
        val s = StickDominance()
        s.dominantAxis(1, -0.9f, -0.2f)
        s.dominantAxis(2, -0.2f, -0.9f)
        assertEquals(DominantAxis.HORIZONTAL, s.dominantAxis(1, -0.7f, -0.7f))
        assertEquals(DominantAxis.VERTICAL, s.dominantAxis(2, -0.7f, -0.7f))
    }

    @Test fun `reset clears every latch`() {
        val s = StickDominance()
        s.dominantAxis(1, -0.9f, -0.2f)
        s.reset()
        assertEquals(DominantAxis.VERTICAL, s.dominantAxis(1, -0.6f, -0.8f))
    }

    @Test fun `the latched axis survives just above the release threshold`() {
        val s = StickDominance()
        s.dominantAxis(1, -0.9f, -0.2f)
        // |x| = 0.35 is still at or above RELEASE_THRESHOLD, so horizontal holds even though
        // vertical is far larger. A magnitude comparison with no hysteresis returns VERTICAL here.
        assertEquals(DominantAxis.HORIZONTAL, s.dominantAxis(1, -0.35f, -0.9f))
        // |x| = 0.25 drops below RELEASE_THRESHOLD, so dominance is re-evaluated and vertical wins.
        assertEquals(DominantAxis.VERTICAL, s.dominantAxis(1, -0.25f, -0.9f))
    }

    @Test fun `a fresh exact tie goes to horizontal`() {
        val s = StickDominance()
        assertEquals(DominantAxis.HORIZONTAL, s.dominantAxis(1, -0.7f, -0.7f))
    }
}
