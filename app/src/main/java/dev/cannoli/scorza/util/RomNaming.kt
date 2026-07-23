package dev.cannoli.scorza.util

object RomNaming {
    private val tagRegex = Regex("""\s*(\([^)]*\)|\[[^\]]*\])""")

    fun splitNameAndTags(rawName: String): Pair<String, String?> {
        val base = tagRegex.replace(rawName, "").trim()
        if (base.isEmpty() || base == rawName) return rawName to null
        val tags = tagRegex.findAll(rawName).joinToString(" ") { it.value.trim() }.takeIf { it.isNotBlank() }
        return base to tags
    }
}
