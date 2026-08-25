package dev.cannoli.scorza.launcher

import android.content.res.AssetManager
import android.os.Build

/**
 * What each core in the APK was when it was bundled, written by `scripts/record_bundled_core.py`
 * during `task cores`.
 *
 * The buildbot has no history to pin to, so a bundled core has no version to ask for and no
 * validator of its own: it came out of the APK, not off the network. Recording both at the moment
 * the build machine fetched it is the only place either is knowable.
 *
 * It answers two questions the app cannot otherwise answer honestly:
 *
 * - **Has this changed?** Without an etag the first update would download all fifteen bundled cores
 *   in full, roughly 48 MB, to discover that most had not changed.
 * - **Which build is this?** Not `display_version`: that is hand-maintained upstream and moves on a
 *   different clock, so gambatte has declared v0.5.0 since 2022 while its binary is rebuilt nightly.
 *   The date the buildbot wrote the file is what actually distinguishes two installs.
 */
object BundledCoreManifest {

    private const val FILE = "bundled_cores.txt"

    /** [built] is the ISO date the buildbot last wrote that binary. */
    data class Entry(val etag: String, val built: String)

    @Volatile private var cached: Map<String, Entry>? = null

    private fun abi(): String =
        Build.SUPPORTED_ABIS?.firstOrNull { it == "arm64-v8a" || it == "armeabi-v7a" } ?: "arm64-v8a"

    /**
     * Keyed by core id, for the ABI this device runs. Entries for the other ABI describe a
     * different binary and must never answer for this one.
     */
    fun read(assets: AssetManager): Map<String, Entry> =
        cached ?: synchronized(this) { cached ?: load(assets).also { if (it.isNotEmpty()) cached = it } }

    private fun load(assets: AssetManager): Map<String, Entry> = try {
        assets.open(FILE).bufferedReader().useLines { parse(it, abi()) }
    } catch (_: Exception) {
        emptyMap()
    }

    /** Rows for [abi] only. The other ABI describes a different binary and must not answer here. */
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

    /** The build identity the APK shipped, or null when this core was not bundled. */
    fun etagFor(assets: AssetManager, coreId: String): String? =
        read(assets)[normalise(coreId)]?.etag

    /** When the bundled binary was built, or null when this core was not bundled. */
    fun builtFor(assets: AssetManager, coreId: String): String? =
        read(assets)[normalise(coreId)]?.built?.takeIf { it != "?" && it.isNotBlank() }

    internal fun resetForTest() {
        cached = null
    }
}
