package dev.cannoli.scorza.ui.screens

import org.junit.Assert.assertTrue
import org.junit.Test

/** AppNavGraph only draws a dialog when [isFullScreen] is true, so a state that renders an overlay
 *  but is missing from that list is set, consumes input, and shows nothing. */
class DialogStateFullScreenTest {

    @Test fun `library switch confirm renders`() {
        val state = DialogState.LibrarySwitchConfirm(newRomDirectory = "/storage/sd/Roms")
        assertTrue(state.isFullScreen)
    }
}
