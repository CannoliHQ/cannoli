package dev.cannoli.scorza.input.autoconfig

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class AutoconfigSeeder(
    private val source: CfgSource,
    private val targetDirProvider: () -> File,
    private val legacyMappingsDirProvider: () -> File,
    private val assetsDigest: String,
    private val buildModel: String,
) {

    private val targetDir: File get() = targetDirProvider()
    private val legacyMappingsDir: File get() = legacyMappingsDirProvider()

    fun seedIfNeeded() {
        val stamp = File(targetDir, STAMP_FILE)
        val want = "$assetsDigest|${buildModel.trim()}"
        if (stamp.takeIf { it.exists() }?.readText()?.trim() == want) return
        targetDir.mkdirs()

        val seeded = mutableSetOf<String>()
        for (name in source.listCfgFiles()) {
            val fileName = name.substringAfterLast('/')
            val text = source.open(name).use { it.readBytes().toString(Charsets.UTF_8) }
            if (!appliesHere(text, fileName)) continue
            seeded += fileName
            val out = File(targetDir, fileName)
            if (out.exists() && isUserOwned(out)) continue
            writeAtomic(out, text)
        }

        prune(seeded)
        legacyMappingsDir.deleteRecursively()
        stamp.writeText(want)
    }

    private fun appliesHere(text: String, fileName: String): Boolean {
        val pin = RetroArchCfgParser.parse(text, fileName = fileName).buildModel?.trim()
        return pin.isNullOrEmpty() || pin.equals(buildModel.trim(), ignoreCase = true)
    }

    private fun prune(seeded: Set<String>) {
        val files = targetDir.listFiles { f: File -> f.isFile && f.extension.equals("cfg", true) } ?: return
        for (file in files) {
            if (file.name in seeded) continue
            if (isUserOwned(file)) continue
            file.delete()
        }
    }

    private fun isUserOwned(file: File): Boolean =
        runCatching { RetroArchCfgParser.parse(file.readText()).isUserOwned }.getOrDefault(false)

    private fun writeAtomic(out: File, text: String) {
        val tmp = File(out.parentFile, "${out.name}.tmp")
        FileOutputStream(tmp).use { fos ->
            fos.write(text.toByteArray())
            fos.fd.sync()
        }
        if (!tmp.renameTo(out)) {
            tmp.delete()
            throw IOException("Failed to rename seeded cfg ${out.name}")
        }
    }

    fun reseedSingle(fileName: String): Boolean = runCatching {
        val asset = source.listCfgFiles().firstOrNull { it.substringAfterLast('/') == fileName }
            ?: return false
        targetDir.mkdirs()
        writeAtomic(File(targetDir, fileName), source.open(asset).use { it.readBytes().toString(Charsets.UTF_8) })
        true
    }.getOrElse { false }

    companion object {
        const val STAMP_FILE = ".seed_version"
    }
}
