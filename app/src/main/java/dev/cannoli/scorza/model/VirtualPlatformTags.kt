package dev.cannoli.scorza.model

object VirtualPlatformTags {
    const val TOOLS = "tools"
    const val PORTS = "ports"
    const val RECENTLY_PLAYED = "recently_played"

    fun isAppList(tag: String?): Boolean = tag == TOOLS || tag == PORTS
}
