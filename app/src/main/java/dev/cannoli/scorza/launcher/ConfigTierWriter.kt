package dev.cannoli.scorza.launcher

import dev.cannoli.core.config.RetroArchConfigComposer
import dev.cannoli.scorza.config.CannoliPaths
import java.io.File

sealed interface ConfigScope {
    data object Global : ConfigScope
    data class System(val tag: String) : ConfigScope
    data class Game(val tag: String, val base: String) : ConfigScope
}

private const val TIER_BANNER =
    "# DO NOT EDIT - Cannoli writes this from your menu choices. Your own keys go in custom.cfg\n"

/**
 * Lean-delta writer for the Cannoli-owned override tiers (global/system/game). Each file holds
 * only the keys the user explicitly set through a menu, one line each, so [LaunchManager] composes
 * them over retroarch.cfg at launch via [RetroArchConfigComposer].
 */
class ConfigTierWriter(private val paths: CannoliPaths) {

    fun set(scope: ConfigScope, key: String, value: String) {
        val file = fileFor(scope)
        val keys = readKeys(file)
        keys[key] = value
        writeKeys(file, keys)
    }

    fun remove(scope: ConfigScope, key: String) {
        val file = fileFor(scope)
        if (!file.exists()) return
        val keys = readKeys(file)
        if (keys.remove(key) == null) return
        writeKeys(file, keys)
    }

    private fun fileFor(scope: ConfigScope): File = when (scope) {
        is ConfigScope.Global -> paths.globalOverrideCfg
        is ConfigScope.System -> paths.systemOverrideCfg(scope.tag)
        is ConfigScope.Game -> paths.gameOverrideCfg(scope.tag, scope.base)
    }

    private fun readKeys(file: File): LinkedHashMap<String, String> =
        if (file.exists()) LinkedHashMap(RetroArchConfigComposer.parse(file.readText())) else LinkedHashMap()

    // Rebuilding the whole file from the map on every write, rather than patching the text in
    // place, is what keeps the last-key-removed case simple: an empty map just leaves the banner.
    private fun writeKeys(file: File, keys: Map<String, String>) {
        file.parentFile?.mkdirs()
        val body = keys.entries.joinToString("") { (k, v) -> "$k = \"$v\"\n" }
        file.writeText(TIER_BANNER + body)
    }
}
