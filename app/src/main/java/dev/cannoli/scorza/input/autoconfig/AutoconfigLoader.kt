package dev.cannoli.scorza.input.autoconfig

class AutoconfigLoader(private val source: CfgSource) {

    private var cached: List<RetroArchCfgEntry>? = null

    fun entries(): List<RetroArchCfgEntry> {
        cached?.let { return it }
        val loaded = source.listCfgFiles().mapNotNull { name ->
            try {
                source.open(name).use { RetroArchCfgParser.parse(it) }
            } catch (_: Exception) {
                null
            }
        }
        cached = loaded
        return loaded
    }
}
