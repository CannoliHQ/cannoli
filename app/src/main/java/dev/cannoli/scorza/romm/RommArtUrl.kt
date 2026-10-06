package dev.cannoli.scorza.romm

import java.net.URI

object RommArtUrl {
    const val RESOURCES_PREFIX = "/assets/romm/resources"

    fun resolve(host: String, path: String?): String? {
        val p = path?.trim().orEmpty()
        if (p.isEmpty()) return null
        if (p.startsWith("http://") || p.startsWith("https://")) {
            return p.takeIf { sameHost(it, host) }
        }
        val rel = if (p.startsWith("/")) p.trimStart('/') else "${RESOURCES_PREFIX.trimStart('/')}/$p"
        return "${host.trimEnd('/')}/$rel"
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

    private fun sameHost(url: String, host: String): Boolean {
        val a = hostOf(url) ?: return false
        val b = hostOf(host) ?: return false
        return a.equals(b, ignoreCase = true)
    }

    private fun hostOf(value: String): String? = runCatching {
        URI(if (value.contains("://")) value else "https://$value").host
    }.getOrNull()
}
