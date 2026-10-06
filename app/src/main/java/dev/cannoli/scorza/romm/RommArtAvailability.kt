package dev.cannoli.scorza.romm

fun RommArtType.mediaKey(): String? = when (this) {
    RommArtType.NONE -> null
    RommArtType.DEFAULT, RommArtType.BOX2D -> "box2d"
    RommArtType.BOX3D -> "box3d"
    RommArtType.MIX -> "miximage"
    RommArtType.TITLE -> "title_screen"
    RommArtType.SCREENSHOT -> "screenshot"
    RommArtType.MARQUEE -> "marquee"
}

fun availableArtTypes(scanMedia: Set<String>): List<RommArtType> {
    if (scanMedia.isEmpty()) return RommArtType.entries
    return RommArtType.entries.filter { it.mediaKey()?.let(scanMedia::contains) ?: true }
}
