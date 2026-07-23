package dev.cannoli.scorza.model

import java.io.File

data class Rom(
    val id: Long,
    val path: File,
    val platformTag: String,
    val displayName: String,
    val tags: String? = null,
    val artFile: File? = null,
    val launchTarget: LaunchTarget = LaunchTarget.RetroArch,
    val raGameId: Int? = null,
    val lastPlayedAt: Long? = null,
    val raCachedGameId: Int? = null,
) {
    val isMultiDisc: Boolean
        get() = path.extension.equals("m3u", ignoreCase = true)
}
