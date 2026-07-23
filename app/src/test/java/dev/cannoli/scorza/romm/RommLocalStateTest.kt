package dev.cannoli.scorza.romm

import org.junit.Assert.assertEquals
import org.junit.Test

class RommLocalStateTest {

    private val present = setOf("game (usa).sfc", "other.sfc")

    @Test fun `present when filename matches`() {
        assertEquals(LocalState.PRESENT, RommLocalState.of("Game (USA).sfc", present))
    }

    @Test fun `remote when filename absent`() {
        assertEquals(LocalState.REMOTE, RommLocalState.of("Missing.sfc", present))
    }

    @Test fun `filename match is case-insensitive`() {
        assertEquals(LocalState.PRESENT, RommLocalState.of("game (usa).SFC", present))
    }
}
