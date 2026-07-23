package dev.cannoli.scorza.romm.sync

enum class ConflictResolution { KEEP_LOCAL, KEEP_SERVER, ESCALATE }

object ConflictAutoResolver {
    fun classify(localHash: String?, localAnchor: String?, serverHash: String?, serverAnchor: String?): ConflictResolution = when {
        localAnchor != null && serverAnchor != null && localHash == localAnchor && serverHash != serverAnchor -> ConflictResolution.KEEP_SERVER
        localAnchor != null && serverAnchor != null && serverHash == serverAnchor && localHash != localAnchor -> ConflictResolution.KEEP_LOCAL
        else -> ConflictResolution.ESCALATE
    }
}
