package dev.cannoli.scorza.romm

object RommVariantFolder {

    fun regionRank(regions: List<String>): Int {
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
