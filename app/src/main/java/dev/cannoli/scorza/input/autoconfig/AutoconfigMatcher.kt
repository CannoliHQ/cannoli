package dev.cannoli.scorza.input.autoconfig

import dev.cannoli.scorza.input.ControllerIdentity

class AutoconfigMatcher(private val entries: List<RetroArchCfgEntry>) {

    fun match(identity: ControllerIdentity): RetroArchCfgEntry? {
        var best: RetroArchCfgEntry? = null
        var bestScore = 0
        for (entry in entries) {
            val score = score(identity, entry)
            if (score > bestScore) {
                best = entry
                bestScore = score
                if (score >= 50) break
            }
        }
        return if (bestScore >= 30) best else null
    }

    private fun score(identity: ControllerIdentity, entry: RetroArchCfgEntry): Int {
        val nameMatch = entry.deviceName.isNotEmpty() && entry.deviceName == identity.name
        val hasVidPid = identity.vendorId != 0 && identity.productId != 0 &&
                entry.vendorId != null && entry.productId != null
        val vidPidMatch = hasVidPid &&
                entry.vendorId == identity.vendorId &&
                entry.productId == identity.productId
        return when {
            nameMatch && vidPidMatch -> 50
            vidPidMatch -> 30
            nameMatch -> 20
            else -> 0
        }
    }
}
