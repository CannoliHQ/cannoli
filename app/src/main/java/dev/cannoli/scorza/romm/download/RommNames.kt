package dev.cannoli.scorza.romm.download

import dev.cannoli.core.RomKey
import java.io.File

fun sanitizeFsName(name: String): String =
    name.replace(Regex("""\s*:\s*"""), " - ")
        .replace(Regex("""[/\\*?"<>|]"""), "_")
        .trim()
        .removeSuffix(" -")

fun guideBaseName(linkRelativePath: String?, fsName: String): String =
    if (linkRelativePath != null) RomKey.baseName(File(linkRelativePath))
    else sanitizeFsName(RomKey.normalize(File(fsName).nameWithoutExtension))

fun adoptGuideDir(guidesForTag: File, from: String, to: String) {
    if (from == to) return
    val src = File(guidesForTag, from)
    if (!src.isDirectory) return
    val dest = File(guidesForTag, to)
    if (!dest.exists() && src.renameTo(dest)) return
    src.copyRecursively(dest, overwrite = true)
    src.deleteRecursively()
}
