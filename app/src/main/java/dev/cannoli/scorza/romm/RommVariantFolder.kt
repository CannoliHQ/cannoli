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
}
