package dev.cannoli.scorza.romm.download

import dev.cannoli.core.RomKey
import java.io.File

fun sanitizeFsName(name: String): String =
    name.replace(Regex("""\s*:\s*"""), " - ")
        .replace(Regex("""[/\\*?"<>|]"""), "_")
        .trim()
        .removeSuffix(" -")

/**
 * Guides are read from Guides/<tag>/<RomKey.baseName of the ROM file the launcher launches>, so a
 * manual must be filed under that same key once the game is linked (that path is the .m3u for a
 * multi-disc bundle) - keyed straight off the installed path so it matches whatever name the
 * installer actually gave the file, sanitized or not - and under the sanitized, NFC-normalized
 * name the ROM will get on download when it is not linked yet.
 */
fun guideBaseName(linkRelativePath: String?, fsName: String): String =
    if (linkRelativePath != null) RomKey.baseName(File(linkRelativePath))
    else sanitizeFsName(RomKey.normalize(File(fsName).nameWithoutExtension))

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
