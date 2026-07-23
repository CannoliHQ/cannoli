package dev.cannoli.scorza.romm

/** The RomM SCAN_MEDIA key backing each art type; NONE has none, so it is always offered. */
fun RommArtType.mediaKey(): String? = when (this) {
    RommArtType.NONE -> null
    RommArtType.DEFAULT, RommArtType.BOX2D -> "box2d"
    RommArtType.BOX3D -> "box3d"
    RommArtType.MIX -> "miximage"
    RommArtType.TITLE -> "title_screen"
    RommArtType.SCREENSHOT -> "screenshot"
    RommArtType.MARQUEE -> "marquee"
}

/**
 * Art types worth offering for an instance whose enabled media is [scanMedia] (from /api/config).
 * Only types the instance actually scrapes are shown, so the picker never lists an option that can
 * only ever render nothing. An empty set means unknown (config not fetched or unsupported) and does
 * not restrict, so a failed fetch never hides a working option.
 */
fun availableArtTypes(scanMedia: Set<String>): List<RommArtType> {
    if (scanMedia.isEmpty()) return RommArtType.entries
    return RommArtType.entries.filter { it.mediaKey()?.let(scanMedia::contains) ?: true }
}
