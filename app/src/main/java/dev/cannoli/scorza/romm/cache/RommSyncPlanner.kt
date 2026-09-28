package dev.cannoli.scorza.romm.cache

object RommSyncPlanner {
    fun nextCursor(current: String?, ingested: List<String?>): String? {
        var max = current
        for (value in ingested) {
            if (value != null && (max == null || value > max)) max = value
        }
        return max
    }

    fun platformsNeedingReconcile(cached: Map<Int, Int>, server: Map<Int, Int>): List<Int> =
        server.filter { (id, serverCount) -> (cached[id] ?: 0) != serverCount }
            .keys
            .sorted()

    fun staleIds(cached: Set<Int>, seen: Set<Int>): Set<Int> = cached - seen

    fun staleLinks(links: List<Pair<Int, String>>, serverIds: Set<Int>, cachedTags: Set<String>): List<Pair<Int, String>> {
        val tags = cachedTags.map { it.uppercase() }.toSet()
        return links.filter { (id, path) -> id !in serverIds && path.substringBefore('/').uppercase() in tags }
    }
}
