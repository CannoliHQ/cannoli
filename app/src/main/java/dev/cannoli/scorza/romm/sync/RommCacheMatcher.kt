package dev.cannoli.scorza.romm.sync

import dev.cannoli.scorza.romm.RommHacks
import dev.cannoli.scorza.romm.cache.RommDatabase

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
