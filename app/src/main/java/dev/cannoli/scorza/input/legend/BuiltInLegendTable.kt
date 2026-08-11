package dev.cannoli.scorza.input.legend

import dev.cannoli.scorza.input.GlyphStyle

object BuiltInLegendTable {
    const val SONY_VID = 0x054C

    // Matched by Build.MODEL.startsWith(prefix, ignoreCase = true); most specific first.
    val byBuildModelPrefix: List<Pair<String, LegendProfile>> = listOf(
        "AYN_Odin_Portal" to LegendProfile(FaceLayout.STANDARD, GlyphStyle.REDMOND),
        "AYN_Thor" to LegendProfile(FaceLayout.NINTENDO, GlyphStyle.PLUMBER),
        "AYN_Odin3" to LegendProfile(FaceLayout.NINTENDO, GlyphStyle.PLUMBER),
    )

    val byVidPid: Map<Pair<Int, Int>, LegendProfile> = mapOf(
        (0x045E to 0x0B12) to LegendProfile(FaceLayout.STANDARD, GlyphStyle.REDMOND),
    )

    val default = LegendProfile(FaceLayout.STANDARD, GlyphStyle.REDMOND)
}
