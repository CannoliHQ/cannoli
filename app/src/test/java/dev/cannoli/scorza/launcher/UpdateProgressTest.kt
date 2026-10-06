package dev.cannoli.scorza.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

class UpdateProgressTest {

    private fun p(done: Long, total: Long) =
        CoreDownloadService.UpdateProgress(done, total)

    @Test fun `a run reports the fraction of bytes it has covered`() {
        assertEquals(0f, p(0, 200).fraction, 0.001f)
        assertEquals(0.5f, p(100, 200).fraction, 0.001f)
        assertEquals(1f, p(200, 200).fraction, 0.001f)
    }

    @Test fun `nothing to do reports zero rather than failing`() {
        assertEquals(0f, p(0, 0).fraction, 0.001f)
        assertEquals(0f, p(50, 0).fraction, 0.001f)
    }

    @Test fun `an overshoot is clamped`() {
        assertEquals(1f, p(300, 200).fraction, 0.001f)
    }
}
