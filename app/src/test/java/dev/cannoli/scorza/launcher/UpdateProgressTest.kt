package dev.cannoli.scorza.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * The bar tracks the whole run weighted by size, because cores range from half a megabyte to 68,
 * and even steps would make the largest look like one twenty-seventh of the work when it is a third
 * of the wait.
 */
class UpdateProgressTest {

    private fun p(done: Long, total: Long) =
        CoreDownloadService.UpdateProgress(done, total)

    @Test fun `a run reports the fraction of bytes it has covered`() {
        assertEquals(0f, p(0, 200).fraction, 0.001f)
        assertEquals(0.5f, p(100, 200).fraction, 0.001f)
        assertEquals(1f, p(200, 200).fraction, 0.001f)
    }

    // A run over an install whose files have gone reports no progress rather than dividing by zero.
    @Test fun `nothing to do reports zero rather than failing`() {
        assertEquals(0f, p(0, 0).fraction, 0.001f)
        assertEquals(0f, p(50, 0).fraction, 0.001f)
    }

    // Weights are estimates from the files on disk, so a build larger than the one it replaces can
    // overshoot. The bar must not run past its end.
    @Test fun `an overshoot is clamped`() {
        assertEquals(1f, p(300, 200).fraction, 0.001f)
    }

    /**
     * The bar renders indeterminate on a null fraction, which is what a sweeping animation instead
     * of a filling one means. A live run must always carry a number, however small.
     */
    @Test fun `a live run always has a fraction to render`() {
        assertNotNull(p(0, 0).fraction)
        assertNotNull(p(1, 200).fraction)
    }
}
