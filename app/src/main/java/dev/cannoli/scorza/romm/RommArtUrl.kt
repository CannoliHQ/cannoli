package dev.cannoli.scorza.romm

import java.net.URI

/**
 * Art is only ever read back from the RomM instance. RomM scrapes the provider server-side and
 * serves its own copy, so anything that does not resolve to a path on the configured RomM host is
 * refused: a game whose art RomM has not stored renders nothing rather than reaching out to
 * ScreenScraper.
 */
object RommArtUrl {
    fun resolve(host: String, coverPath: String?): String? {
        val cover = coverPath?.trim().orEmpty()
        if (cover.isEmpty()) return null
        if (cover.startsWith("http://") || cover.startsWith("https://")) {
            return cover.takeIf { hostOf(it) != null && hostOf(it).equals(hostOf(host), ignoreCase = true) }
        }
        return "${host.trimEnd('/')}/${cover.trimStart('/')}"
    }

    fun forType(host: String, game: RommGame, artType: RommArtType): String? {
        val path = when (artType) {
            RommArtType.NONE -> return null
            RommArtType.DEFAULT -> game.coverPath
            RommArtType.BOX2D -> game.coverPath
            RommArtType.BOX3D -> game.ssMedia?.box3dPath
            RommArtType.MIX -> game.ssMedia?.mixPath
            RommArtType.TITLE -> game.ssMedia?.titleScreenPath
            RommArtType.SCREENSHOT -> game.screenshotPath
            RommArtType.MARQUEE -> game.ssMedia?.marqueePath
        }
        return resolve(host, path)
    }

    private fun hostOf(value: String): String? = runCatching {
        URI(if (value.contains("://")) value else "https://$value").host
    }.getOrNull()
}
