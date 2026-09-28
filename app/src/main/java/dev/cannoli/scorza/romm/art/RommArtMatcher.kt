package dev.cannoli.scorza.romm.art

import dev.cannoli.scorza.romm.RommGame
import dev.cannoli.scorza.romm.RommHacks

sealed interface ArtOutcome {
    data object AlreadyHasArt : ArtOutcome
    data class Found(val game: RommGame) : ArtOutcome
    data object NoMatch : ArtOutcome
}

object RommArtMatcher {
    /**
     * The [decide] name index: each entry's fs name, then its top-level game file, then its hack
     * files, so a hack takes its parent entry's cover. An earlier key is never displaced.
     */
    fun byFileName(games: List<RommGame>): Map<String, RommGame> {
        val index = HashMap<String, RommGame>()
        games.forEach { index.putIfAbsent(it.fsName.lowercase(), it) }
        games.forEach { g -> RommHacks.baseFileName(g)?.let { index.putIfAbsent(it.lowercase(), g) } }
        games.forEach { g -> RommHacks.hackFiles(g).forEach { index.putIfAbsent(it.fileName.lowercase(), g) } }
        return index
    }

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
