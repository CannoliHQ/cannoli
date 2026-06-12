package dev.cannoli.scorza.romm.art

import dev.cannoli.scorza.romm.RommGame

sealed interface ArtOutcome {
    data object AlreadyHasArt : ArtOutcome
    data class Found(val game: RommGame) : ArtOutcome
    data object NoMatch : ArtOutcome
}

object RommArtMatcher {
    /**
     * Decide what to do for one local game. [linkedRommId] is the romm_id linked to the local
     * file (or null); [byFsName] is keyed by lowercased fs name, [byId] by romm id.
     */
    fun decide(
        hasArt: Boolean,
        fsName: String,
        linkedRommId: Int?,
        byFsName: Map<String, RommGame>,
        byId: Map<Int, RommGame>,
    ): ArtOutcome {
        if (hasArt) return ArtOutcome.AlreadyHasArt
        val game = linkedRommId?.let { byId[it] } ?: byFsName[fsName.lowercase()]
        if (game == null || game.coverPath.isNullOrBlank()) return ArtOutcome.NoMatch
        return ArtOutcome.Found(game)
    }
}
