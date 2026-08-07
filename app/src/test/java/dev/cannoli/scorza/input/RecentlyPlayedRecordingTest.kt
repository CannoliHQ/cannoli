package dev.cannoli.scorza.input

import dev.cannoli.scorza.input.LauncherActions.PlayOutcome
import dev.cannoli.scorza.input.LauncherActions.Companion.playOutcomeFor
import dev.cannoli.scorza.ui.screens.DialogState
import org.junit.Assert.assertEquals
import org.junit.Test

class RecentlyPlayedRecordingTest {

    @Test fun `the launch scrim is a play, not a failure`() {
        assertEquals(
            PlayOutcome.RECORD,
            playOutcomeFor(DialogState.Launching, saveSyncChecking = false),
        )
    }

    @Test fun `a launch that returns nothing is a play`() {
        assertEquals(PlayOutcome.RECORD, playOutcomeFor(null, saveSyncChecking = false))
    }

    @Test fun `a missing core is not a play`() {
        assertEquals(
            PlayOutcome.IGNORE,
            playOutcomeFor(DialogState.MissingCore("mgba_libretro"), saveSyncChecking = false),
        )
    }

    @Test fun `a launch error is not a play`() {
        assertEquals(
            PlayOutcome.IGNORE,
            playOutcomeFor(DialogState.LaunchError("boom"), saveSyncChecking = false),
        )
    }

    @Test fun `a save sync check holds the play`() {
        assertEquals(PlayOutcome.HOLD, playOutcomeFor(null, saveSyncChecking = true))
    }

    @Test fun `a failure during a save sync check is still not a play`() {
        assertEquals(
            PlayOutcome.IGNORE,
            playOutcomeFor(DialogState.LaunchError("boom"), saveSyncChecking = true),
        )
    }
}
