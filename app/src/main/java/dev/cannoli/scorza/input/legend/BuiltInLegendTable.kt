package dev.cannoli.scorza.input.legend

import dev.cannoli.scorza.input.GlyphStyle

object BuiltInLegendTable {
    const val SONY_VID = 0x054C

    data class BuiltInEntry(
        val modelPrefix: String,
        val vendorId: Int,
        val productId: Int,
        val profile: LegendProfile,
    )

    // A supported handheld's built-in pad. Matched by Build.MODEL prefix AND the built-in pad's
    // own vid/pid, so an external pad plugged into the same device does not inherit this legend.
    // Most specific model prefix first.
    val builtIns: List<BuiltInEntry> = listOf(
        BuiltInEntry("AYN_Odin_Portal", 0x2020, 0x0111, LegendProfile(FaceLayout.STANDARD, GlyphStyle.REDMOND)),
        BuiltInEntry("AYN_Thor", 0x2020, 0x0111, LegendProfile(FaceLayout.NINTENDO, GlyphStyle.PLUMBER)),
        BuiltInEntry("AYN_Odin3", 0x2020, 0x0111, LegendProfile(FaceLayout.NINTENDO, GlyphStyle.PLUMBER)),
    )

    val byVidPid: Map<Pair<Int, Int>, LegendProfile> = mapOf(
        (0x045E to 0x0B12) to LegendProfile(FaceLayout.STANDARD, GlyphStyle.REDMOND),
    )

    val default = LegendProfile(FaceLayout.STANDARD, GlyphStyle.REDMOND)
}
