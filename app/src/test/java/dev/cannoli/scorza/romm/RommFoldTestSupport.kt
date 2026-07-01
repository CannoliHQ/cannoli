package dev.cannoli.scorza.romm

import dev.cannoli.scorza.util.NaturalSort

// Mirrors the DB window fold (partition by platform+group_key, representative by main sibling then
// region rank then natural name, ordered by name) so browse-VM fakes can return RommFoldedGame the
// same shape CachedRommLibrary would.
fun fakeFold(games: List<RommGame>): List<RommFoldedGame> =
    games.groupBy { it.platformId to if (it.groupKey > 0) it.groupKey else -it.id }
        .map { (_, members) ->
            val rep = members.minWithOrNull(
                compareByDescending<RommGame> { it.isMainSibling }
                    .thenBy { RommVariantFolder.regionRank(it.regions) }
                    .thenBy(NaturalSort) { it.name }
            ) ?: members.first()
            RommFoldedGame(rep, members.size, members.map { it.id }, members.map { it.fsName })
        }
        .sortedWith(compareBy(NaturalSort) { it.game.name })
