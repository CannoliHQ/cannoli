package dev.cannoli.scorza.romm

object RommArtUrl {
    fun resolve(host: String, coverPath: String?): String? {
        val cover = coverPath?.trim().orEmpty()
        if (cover.isEmpty()) return null
        if (cover.startsWith("http://") || cover.startsWith("https://")) return cover
        return "${host.trimEnd('/')}/${cover.trimStart('/')}"
    }

    fun forType(host: String, game: RommGame, artType: RommArtType): String? {
        val url = when (artType) {
            RommArtType.NONE -> return null
            RommArtType.DEFAULT -> game.coverPath
            RommArtType.BOX2D -> game.ssMedia?.box2d ?: game.coverPath
            RommArtType.BOX3D -> game.ssMedia?.box3d
            RommArtType.MIX -> game.ssMedia?.mix
            RommArtType.TITLE -> game.ssMedia?.titleScreen
            RommArtType.SCREENSHOT -> game.ssMedia?.screenshot
            RommArtType.MARQUEE -> game.ssMedia?.marquee
        }
        return resolve(host, url)
    }
}
