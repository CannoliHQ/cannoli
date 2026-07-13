package dev.cannoli.scorza.romm.download

import java.io.File

fun sanitizeFsName(name: String): String = name.replace(Regex("[/\\\\:*?\"<>|]"), "_").trim()

/**
 * Guides are read from Guides/<tag>/<base name of the ROM file the launcher launches>, so a manual
 * must be filed under the installed path once the game is linked (that path is the .m3u for a
 * multi-disc bundle) and under the name the ROM will get on download when it is not.
 */
fun guideBaseName(linkRelativePath: String?, fsName: String): String =
    sanitizeFsName(File(linkRelativePath ?: fsName).nameWithoutExtension)

/** Re-files guides written under the pre-download name once an install settles the real base name. */
fun adoptGuideDir(guidesForTag: File, from: String, to: String) {
    if (from == to) return
    val src = File(guidesForTag, from)
    if (!src.isDirectory) return
    val dest = File(guidesForTag, to)
    if (!dest.exists() && src.renameTo(dest)) return
    src.copyRecursively(dest, overwrite = true)
    src.deleteRecursively()
}
