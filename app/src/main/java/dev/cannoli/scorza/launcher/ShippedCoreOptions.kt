package dev.cannoli.scorza.launcher

import dev.cannoli.scorza.input.GlyphStyle

internal object ShippedCoreOptions {

    private fun shipped(core: String, glyphStyle: GlyphStyle?): Map<String, String> =
        if (core == "picodrive_libretro" && glyphStyle == GlyphStyle.HEDGEHOG_6) {
            mapOf("picodrive_input1" to "6 button pad", "picodrive_input2" to "6 button pad")
        } else {
            emptyMap()
        }

    fun compose(
        core: String,
        glyphStyle: GlyphStyle?,
        platform: Map<String, String>,
        game: Map<String, String>,
    ): Map<String, String> = LinkedHashMap<String, String>().apply {
        putAll(shipped(core, glyphStyle))
        putAll(platform)
        putAll(game)
    }
}
