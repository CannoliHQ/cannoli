package dev.cannoli.scorza.model

/** List identities shaped like a platform tag that name no directory under the ROM root. */
object VirtualPlatformTags {
    const val TOOLS = "tools"
    const val PORTS = "ports"
    const val RECENTLY_PLAYED = "recently_played"

    /** Tools and Ports list installed apps rather than files, so most list code treats them as one. */
    fun isAppList(tag: String?): Boolean = tag == TOOLS || tag == PORTS
}
