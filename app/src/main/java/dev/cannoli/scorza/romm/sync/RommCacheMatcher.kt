package dev.cannoli.scorza.romm.sync

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

    private fun build(): Map<String, Map<String, Int>> {
        val result = HashMap<String, HashMap<String, Int>>()
        for (platform in cache.platforms()) {
            val byName = result.getOrPut(platform.cannoliTag.uppercase()) { HashMap() }
            for (game in cache.allGames(platform.id)) {
                byName.putIfAbsent(game.fsName.lowercase(), game.id)
            }
        }
        return result
    }
}
