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
    // modelPrefix is the literal ro.product.model (Build.MODEL) including its spaces; do NOT use
    // the underscore-sanitized value that `adb devices -l` prints. Most specific prefix first.
    val builtIns: List<BuiltInEntry> = listOf(
        // Verified on-device: ro.product.model = "AYN Thor".
        BuiltInEntry("AYN Thor", 0x2020, 0x0111, LegendProfile(FaceLayout.NINTENDO, GlyphStyle.PLUMBER)),
        // Unverified model strings; confirm ro.product.model on each device before trusting.
        BuiltInEntry("AYN Odin Portal", 0x2020, 0x0111, LegendProfile(FaceLayout.STANDARD, GlyphStyle.REDMOND)),
        BuiltInEntry("AYN Odin 3", 0x2020, 0x0111, LegendProfile(FaceLayout.NINTENDO, GlyphStyle.PLUMBER)),
    )

    val byVidPid: Map<Pair<Int, Int>, LegendProfile> = mapOf(
        (0x045E to 0x0B12) to LegendProfile(FaceLayout.STANDARD, GlyphStyle.REDMOND),
        // Google Stadia Controller (0x18D1:0x9400), ported from controller_hints.json.
        (0x18D1 to 0x9400) to LegendProfile(FaceLayout.STANDARD, GlyphStyle.REDMOND),
    )

    val default = LegendProfile(FaceLayout.STANDARD, GlyphStyle.REDMOND)
}
