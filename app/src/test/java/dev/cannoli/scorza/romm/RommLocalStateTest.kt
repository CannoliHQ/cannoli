package dev.cannoli.scorza.romm

import org.junit.Assert.assertEquals
import org.junit.Test

class RommLocalStateTest {

    private val local = listOf(
        LocalFile("Game (USA).sfc", 1048576L),
        LocalFile("Other.sfc", 999L),
    )

    @Test fun `present when filename and size match`() {
        assertEquals(LocalState.PRESENT,
            RommLocalState.of(fsName = "Game (USA).sfc", sizeBytes = 1048576L, localFiles = local))
    }

    @Test fun `remote when filename absent`() {
        assertEquals(LocalState.REMOTE,
            RommLocalState.of(fsName = "Missing.sfc", sizeBytes = 1L, localFiles = local))
    }

    @Test fun `remote when name matches but size differs`() {
        assertEquals(LocalState.REMOTE,
            RommLocalState.of(fsName = "Game (USA).sfc", sizeBytes = 2L, localFiles = local))
    }

    @Test fun `filename match is case-insensitive`() {
        assertEquals(LocalState.PRESENT,
            RommLocalState.of(fsName = "game (usa).SFC", sizeBytes = 1048576L, localFiles = local))
    }
}
