package dev.cannoli.scorza.romm

enum class LocalState { PRESENT, REMOTE }

object RommLocalState {
    fun of(fsName: String, presentFileNames: Set<String>): LocalState =
        if (fsName.lowercase() in presentFileNames) LocalState.PRESENT else LocalState.REMOTE
}
