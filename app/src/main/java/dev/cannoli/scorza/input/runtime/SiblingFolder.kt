package dev.cannoli.scorza.input.runtime

object SiblingFolder {

    private const val MIN_PREFIX_LEN = 5

    private const val MAX_ID_GAP = 1

    data class Candidate(
        val androidDeviceId: Int,
        val name: String,
        val descriptor: String,
        val isGamepad: Boolean,
    )

    data class Cluster(
        val gamepad: Candidate,
        val aliases: List<Candidate>,
    )

    fun fold(candidates: List<Candidate>): List<Cluster> {
        if (candidates.isEmpty()) return emptyList()
        val sorted = candidates.sortedBy { it.androidDeviceId }
        val groups = mutableListOf<List<Candidate>>()
        var current = mutableListOf<Candidate>()
        var currentRoot = ""
        for (c in sorted) {
            if (current.isEmpty()) {
                current.add(c)
                currentRoot = c.name
                continue
            }
            val terminated = current.any { it.isGamepad }
            val lastId = current.last().androidDeviceId
            val idAdjacent = (c.androidDeviceId - lastId) <= MAX_ID_GAP
            val sharedLen = sharedPrefixLength(currentRoot, c.name)
            val nameShared = sharedLen >= MIN_PREFIX_LEN
            if (!terminated && idAdjacent && nameShared) {
                current.add(c)
                if (sharedLen < currentRoot.length) {
                    currentRoot = currentRoot.substring(0, sharedLen)
                }
            } else {
                groups.add(current)
                current = mutableListOf(c)
                currentRoot = c.name
            }
        }
        if (current.isNotEmpty()) groups.add(current)

        val out = mutableListOf<Cluster>()
        for (group in groups) {
            val gamepad = group.firstOrNull { it.isGamepad } ?: continue
            val aliases = group.filter { it.androidDeviceId != gamepad.androidDeviceId }
            out.add(Cluster(gamepad = gamepad, aliases = aliases))
        }
        return out
    }

    private fun sharedPrefixLength(a: String, b: String): Int {
        val rawCommon = a.commonPrefixWith(b)
        if (rawCommon.length < MIN_PREFIX_LEN) return 0
        val aRest = a.substring(rawCommon.length)
        val bRest = b.substring(rawCommon.length)
        val endsInWhitespace = rawCommon.last().isWhitespace()
        val aBoundary = aRest.isEmpty() || aRest[0].isWhitespace()
        val bBoundary = bRest.isEmpty() || bRest[0].isWhitespace()
        if (!endsInWhitespace && !aBoundary && !bBoundary) return 0
        return rawCommon.trimEnd().length
    }
}
