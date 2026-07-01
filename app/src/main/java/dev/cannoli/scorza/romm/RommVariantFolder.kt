package dev.cannoli.scorza.romm

import dev.cannoli.scorza.util.NaturalSort

data class RommGroup(val representative: RommGame, val members: List<RommGame>) {
    val count: Int get() = members.size
}

object RommVariantFolder {

    fun fold(games: List<RommGame>): List<RommGroup> =
        games.groupBy { it.platformId to effectiveGroupKey(it) }
            .map { (_, members) -> RommGroup(chooseRepresentative(members), members) }

    // A game with no real sibling key (unset sentinel) folds only with itself, never with unrelated
    // singletons that share the sentinel. Platform is part of the key so cross-platform never merges.
    private fun effectiveGroupKey(game: RommGame): Int =
        if (game.groupKey > 0) game.groupKey else -game.id

    fun foldSorted(games: List<RommGame>): List<RommGroup> =
        fold(games).sortedWith(compareBy(NaturalSort) { it.representative.name })

    private fun chooseRepresentative(members: List<RommGame>): RommGame =
        members.minWithOrNull(
            compareByDescending<RommGame> { it.isMainSibling }
                .thenBy { regionRank(it.regions) }
                .thenBy { it.fsName.lowercase() }
        ) ?: members.first()

    private fun regionRank(regions: List<String>): Int {
        val r = regions.map { it.lowercase() }
        return when {
            r.any { it == "usa" || it == "world" } -> 0
            r.any { it == "europe" } -> 1
            r.any { it == "japan" } -> 2
            else -> 3
        }
    }

    fun variantLabel(game: RommGame, formatRev: (String) -> String): String {
        if (game.regions.isEmpty()) return game.fsName.substringBeforeLast('.')
        val base = game.regions.joinToString("/")
        val rev = game.revision?.takeIf { it.isNotBlank() }
        return if (rev != null) "$base (${formatRev(rev)})" else base
    }
}
