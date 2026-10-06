package dev.cannoli.scorza.input.resolver

import dev.cannoli.scorza.input.autoconfig.RetroArchCfgEntry
import dev.cannoli.scorza.input.ConnectedDevice

enum class MatchRank {
    NAME_AND_MODEL,
    NAME_AND_VID_PID,
    NAME_ONLY,
    ALIAS,
    VID_PID_UNNAMED,
}

object IdentityMatcher {

    fun rank(entry: RetroArchCfgEntry, device: ConnectedDevice): MatchRank? {
        val entryName = entry.deviceName.trim()
        val deviceName = device.name.trim()
        val aliasMatch = deviceName.isNotEmpty() && entry.deviceAliases.any { it.trim() == deviceName }
        if (entryName.isNotEmpty() && deviceName.isNotEmpty() && entryName != deviceName && !aliasMatch) return null

        val pin = entry.buildModel?.trim()
        if (!pin.isNullOrEmpty() && !pin.equals(device.androidBuildModel.trim(), ignoreCase = true)) return null

        val nameMatch = entryName.isNotEmpty() && entryName == deviceName
        val vidPidMatch = device.vendorId != 0 && device.productId != 0 &&
            entry.vendorId == device.vendorId && entry.productId == device.productId

        return when {
            nameMatch && !pin.isNullOrEmpty() -> MatchRank.NAME_AND_MODEL
            nameMatch && vidPidMatch -> MatchRank.NAME_AND_VID_PID
            nameMatch -> MatchRank.NAME_ONLY
            aliasMatch -> MatchRank.ALIAS
            entryName.isEmpty() && vidPidMatch -> MatchRank.VID_PID_UNNAMED
            else -> null
        }
    }

    fun builtinAgrees(entry: RetroArchCfgEntry, device: ConnectedDevice): Boolean =
        entry.builtin == null || entry.builtin == device.isBuiltIn
}
