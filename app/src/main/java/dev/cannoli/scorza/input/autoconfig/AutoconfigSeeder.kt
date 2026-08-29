package dev.cannoli.scorza.input.autoconfig

import java.io.File

class AutoconfigSeeder(
    private val source: CfgSource,
    private val targetDirProvider: () -> File,
    private val assetsDigest: String,
    private val buildModel: String,
) {

    private val targetDir: File get() = targetDirProvider()

    fun seedIfNeeded() {
        val stamp = File(targetDir, STAMP_FILE)
        val want = "$assetsDigest|${buildModel.trim()}"
        if (stamp.takeIf { it.exists() }?.readText()?.trim() == want) return
        targetDir.mkdirs()

        val allNames = source.listCfgFiles()
        val bundledNames = allNames.mapTo(mutableSetOf()) { it.substringAfterLast('/') }
        val seeded = mutableSetOf<String>()
        for (name in allNames) {
            val fileName = name.substringAfterLast('/')
            val text = source.open(name).use { it.readBytes().toString(Charsets.UTF_8) }
            if (!appliesHere(text, fileName)) continue
            seeded += fileName
            val out = File(targetDir, fileName)
            if (out.exists() && isUserOwned(out)) continue
            writeCfgAtomic(out, text)
        }

        prune(seeded, bundledNames)
        stamp.writeText(want)
    }

    private fun appliesHere(text: String, fileName: String): Boolean {
        val pin = RetroArchCfgParser.parse(text, fileName = fileName).buildModel?.trim()
        return pin.isNullOrEmpty() || pin.equals(buildModel.trim(), ignoreCase = true)
    }

    // Prunable only if explicitly INPUT_DB, or unkeyed under a name we currently ship: an unkeyed
    // cfg under any other name may be one RetroArch itself wrote into this directory, or one the
    // user hand-dropped in, and neither carries a cannoli_ key to tell it apart.
    private fun prune(seeded: Set<String>, bundledNames: Set<String>) {
        val files = targetDir.listFiles { f: File -> f.isFile && f.extension.equals("cfg", true) } ?: return
        for (file in files) {
            if (file.name in seeded) continue
            val entry = runCatching { RetroArchCfgParser.parse(file.readText()) }.getOrNull() ?: continue
            if (entry.isUserOwned) continue
            val prunable = entry.provenance == CfgProvenance.INPUT_DB ||
                (entry.provenance == null && file.name in bundledNames)
            if (prunable) file.delete()
        }
    }

    // A file that cannot be read or parsed must be treated as user owned, so a transient read
    // failure skips the overwrite instead of silently clobbering a hand-tuned cfg.
    private fun isUserOwned(file: File): Boolean =
        runCatching { RetroArchCfgParser.parse(file.readText()).isUserOwned }.getOrDefault(true)

    // Reset restores the bundled cfg instead of only dropping the user's, because an edit is saved
    // over the file it was resolved from and RetroArch reads the same directory.
    fun reseedSingle(fileName: String): Boolean = runCatching {
        val asset = source.listCfgFiles().firstOrNull { it.substringAfterLast('/') == fileName }
            ?: return false
        targetDir.mkdirs()
        writeCfgAtomic(File(targetDir, fileName), source.open(asset).use { it.readBytes().toString(Charsets.UTF_8) })
        true
    }.getOrElse { false }

    companion object {
        const val STAMP_FILE = ".seed_version"
    }
}
