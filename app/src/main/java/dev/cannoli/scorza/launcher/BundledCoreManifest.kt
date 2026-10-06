package dev.cannoli.scorza.launcher

import android.content.res.AssetManager

object BundledCoreManifest {

    private const val FILE = "bundled_cores.txt"

    data class Entry(val etag: String, val built: String)

    @Volatile private var cached: Map<String, Entry>? = null

    private fun abi(): String = DeviceAbi.primary()

    fun read(assets: AssetManager): Map<String, Entry> =
        cached ?: synchronized(this) { cached ?: load(assets).also { if (it.isNotEmpty()) cached = it } }

    private fun load(assets: AssetManager): Map<String, Entry> = try {
        assets.open(FILE).bufferedReader().useLines { parse(it, abi()) }
    } catch (_: Exception) {
        emptyMap()
    }

    internal fun parse(lines: Sequence<String>, abi: String): Map<String, Entry> =
        lines.mapNotNull { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) return@mapNotNull null
            val parts = trimmed.split(Regex("\\s+"), limit = 4)
            if (parts.size < 4 || parts[0] != abi) return@mapNotNull null
            parts[1] to Entry(parts[2], parts[3])
        }.toMap()

    private fun normalise(coreId: String): String =
        if (coreId.endsWith("_libretro")) coreId else "${coreId}_libretro"

    fun etagFor(assets: AssetManager, coreId: String): String? =
        read(assets)[normalise(coreId)]?.etag

    fun builtFor(assets: AssetManager, coreId: String): String? =
        read(assets)[normalise(coreId)]?.built?.takeIf { it != "?" && it.isNotBlank() }
}
