package dev.cannoli.scorza.romm.sync

import dev.cannoli.scorza.romm.RommHacks
import dev.cannoli.scorza.romm.cache.RommDatabase

/**
 * Resolves the RomM rom id for a local game by matching its filename against the cached RomM
 * library, the same way the browse screen decides a game is "downloaded". This is what lets save
 * sync cover pre-RomM games that were never downloaded through RomM but match a server entry.
 */
class RommCacheMatcher(private val cache: RommDatabase) {
    @Volatile private var index: Map<String, Map<String, Int>>? = null

    fun refresh() {
        index = build()
    }

    fun rommIdFor(tag: String, fileName: String): Int? {
        val idx = index ?: build().also { index = it }
        return idx[tag.uppercase()]?.get(fileName.lowercase())
    }

    // Hack file names are never indexed: a hack shares its entry's id, and resolving one here would
    // sync the hack's saves into the base game's.
    private fun build(): Map<String, Map<String, Int>> {
        val result = HashMap<String, HashMap<String, Int>>()
        for (platform in cache.platforms()) {
            val byName = result.getOrPut(platform.cannoliTag.uppercase()) { HashMap() }
            val games = cache.allGames(platform.id)
            for (game in games) {
                byName.putIfAbsent(game.fsName.lowercase(), game.id)
            }
            for (game in games) {
                val base = RommHacks.baseFileName(game) ?: continue
                byName.putIfAbsent(base.lowercase(), game.id)
            }
        }
        return result
    }
}
