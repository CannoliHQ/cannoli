package dev.cannoli.scorza.launcher

import android.net.Uri

internal data class AndroidShortcutTarget(
    val packageName: String,
    val shortcutId: String,
) {
    fun encode(): String = Uri.Builder()
        .scheme(SCHEME)
        .authority(packageName)
        .appendPath(shortcutId)
        .build()
        .toString()

    companion object {
        private const val SCHEME = "cannoli-shortcut"

        fun decode(value: String): AndroidShortcutTarget? {
            val uri = runCatching { Uri.parse(value) }.getOrNull() ?: return null
            if (uri.scheme != SCHEME) return null
            val packageName = uri.authority?.takeIf { it.isNotBlank() } ?: return null
            val shortcutId = uri.pathSegments.singleOrNull()?.takeIf { it.isNotBlank() }
                ?: return null
            return AndroidShortcutTarget(packageName, shortcutId)
        }
    }
}
