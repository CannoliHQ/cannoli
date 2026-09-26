package dev.cannoli.scorza.settings

import dev.cannoli.igm.ShortcutAction
import dev.cannoli.core.IniParser
import dev.cannoli.core.IniWriter
import dev.cannoli.scorza.config.CannoliPaths

class GlobalOverridesManager(private val sdCardRoot: () -> String) {

    private fun iniFile() = CannoliPaths(sdCardRoot()).shortcutsIni

    fun readShortcuts(): Map<ShortcutAction, Set<Int>> {
        val ini = IniParser.parse(iniFile())
        val map = mutableMapOf<ShortcutAction, Set<Int>>()
        for ((key, value) in ini.getSection("shortcuts")) {
            val action = try { ShortcutAction.valueOf(key) } catch (_: IllegalArgumentException) { continue }
            val chord = if (value.isEmpty()) emptySet()
            else value.split(",").mapNotNull { it.toIntOrNull() }.toSet()
            map[action] = chord
        }
        return map
    }

    private val savedListeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    /** Told after every save, so a reader holding the bindings in memory picks the change up. */
    fun addShortcutsSavedListener(listener: () -> Unit) {
        savedListeners += listener
    }

    fun removeShortcutsSavedListener(listener: () -> Unit) {
        savedListeners -= listener
    }

    fun saveShortcuts(shortcuts: Map<ShortcutAction, Set<Int>>) {
        IniWriter.mergeWrite(
            iniFile(), "shortcuts",
            shortcuts.mapKeys { it.key.name }.mapValues { it.value.joinToString(",") }
        )
        savedListeners.forEach { it() }
    }
}
