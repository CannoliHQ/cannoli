package dev.cannoli.scorza.romm

enum class LocalState { PRESENT, REMOTE }

object RommLocalState {
    fun of(fsName: String, presentFileNames: Set<String>): LocalState =
        if (fsName.lowercase() in presentFileNames) LocalState.PRESENT else LocalState.REMOTE

    /** [game] is on the device when a local file carries any of its [RommHacks.matchNames]. */
    fun of(game: RommGame, presentFileNames: Set<String>): LocalState =
        if (RommHacks.matchNames(game).any { it.lowercase() in presentFileNames }) LocalState.PRESENT
        else LocalState.REMOTE
}
