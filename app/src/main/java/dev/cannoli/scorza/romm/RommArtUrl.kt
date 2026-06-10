package dev.cannoli.scorza.romm

object RommArtUrl {
    fun resolve(host: String, coverPath: String?): String? {
        val cover = coverPath?.trim().orEmpty()
        if (cover.isEmpty()) return null
        if (cover.startsWith("http://") || cover.startsWith("https://")) return cover
        return "${host.trimEnd('/')}/${cover.trimStart('/')}"
    }
}
