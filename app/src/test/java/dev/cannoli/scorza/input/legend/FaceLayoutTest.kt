package dev.cannoli.scorza.input.legend

import dev.cannoli.igm.CanonicalButton
import org.junit.Assert.assertEquals
import org.junit.Test

class FaceLayoutTest {
    @Test fun `standard puts A at south and B at east, confirm south`() {
        val b = FaceLayout.STANDARD.standardFaceBindings()
        assertEquals(96, b[CanonicalButton.BTN_SOUTH])
        assertEquals(97, b[CanonicalButton.BTN_EAST])
        assertEquals(99, b[CanonicalButton.BTN_WEST])
        assertEquals(100, b[CanonicalButton.BTN_NORTH])
        assertEquals(CanonicalButton.BTN_SOUTH, FaceLayout.STANDARD.confirmButton)
    }

    @Test fun `nintendo swaps A_B and X_Y, confirm east`() {
        val b = FaceLayout.NINTENDO.standardFaceBindings()
        assertEquals(97, b[CanonicalButton.BTN_SOUTH])
        assertEquals(96, b[CanonicalButton.BTN_EAST])
        assertEquals(99, b[CanonicalButton.BTN_NORTH])
        assertEquals(100, b[CanonicalButton.BTN_WEST])
        assertEquals(CanonicalButton.BTN_EAST, FaceLayout.NINTENDO.confirmButton)
    }
}
