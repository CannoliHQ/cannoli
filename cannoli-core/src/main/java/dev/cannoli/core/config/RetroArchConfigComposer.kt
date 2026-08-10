package dev.cannoli.core.config

/**
 * Pure merge logic for a RetroArch cfg's `key = "value"` lines, shared by every layer that
 * writes on top of a base config (launch overrides today, the full preference layer stack later).
 */
object RetroArchConfigComposer {

    // Parse a cfg's `key = "value"` lines to an ordered map. Lenient: blank lines,
    // comments, and malformed lines are dropped (caller logs). First occurrence wins,
    // matching RetroArch's config_file insert-if-absent semantics.
    fun parse(text: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        for (line in text.lines()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#") || '=' !in trimmed) continue
            val key = trimmed.substringBefore('=').trim()
            if (key.isEmpty()) continue
            val value = trimmed.substringAfter('=').trim().removeSurrounding("\"")
            if (key !in result) result[key] = value
        }
        return result
    }

    // Fold preference layers over the base text (later layer overrides earlier),
    // reusing applyOverrides semantics. Returns the merged base string.
    fun compose(base: String, layers: List<Map<String, String>>): String =
        layers.fold(base) { source, overrides -> applyOverrides(source, overrides) }

    private fun applyOverrides(source: String, overrides: Map<String, String>): String {
        val applied = mutableSetOf<String>()
        val lines = source.lines().map { line ->
            val trimmed = line.trimStart()
            val key = trimmed.substringBefore('=').trim().removePrefix("# ")
            if (key in overrides) {
                applied.add(key)
                "$key = \"${overrides[key]}\""
            } else line
        }.toMutableList()
        for ((key, value) in overrides) {
            if (key !in applied) lines.add("$key = \"$value\"")
        }
        return lines.joinToString("\n")
    }
}
