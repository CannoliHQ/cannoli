package dev.cannoli.scorza.launcher

import android.net.Uri

internal data class GameHubTarget(
    val packageName: String,
    val steamAppId: String,
    val title: String,
) {
    fun encode(): String = Uri.Builder()
        .scheme(SCHEME)
        .authority(packageName)
        .appendPath(steamAppId)
        .appendQueryParameter(TITLE, title)
        .build()
        .toString()

    companion object {
        const val GAMEHUB_LITE_PACKAGE = "gamehub.lite"
        private const val SCHEME = "cannoli-gamehub"
        private const val TITLE = "title"

        val FEATURED_GAMES = listOf(
            GameHubTarget(GAMEHUB_LITE_PACKAGE, "200900", "Cave Story+"),
            GameHubTarget(GAMEHUB_LITE_PACKAGE, "413150", "Stardew Valley"),
            GameHubTarget(GAMEHUB_LITE_PACKAGE, "1809540", "Nine Sols"),
            GameHubTarget(GAMEHUB_LITE_PACKAGE, "204630", "Retro City Rampage DX"),
        )

        fun decode(value: String): GameHubTarget? {
            val uri = runCatching { Uri.parse(value) }.getOrNull() ?: return null
            if (uri.scheme != SCHEME) return null
            val packageName = uri.authority?.takeIf { it.isNotBlank() } ?: return null
            val steamAppId = uri.pathSegments.singleOrNull()
                ?.takeIf { it.isNotBlank() && it.all(Char::isDigit) }
                ?: return null
            val title = uri.getQueryParameter(TITLE)?.takeIf { it.isNotBlank() } ?: return null
            return GameHubTarget(packageName, steamAppId, title)
        }
    }
}
