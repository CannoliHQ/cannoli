package dev.cannoli.scorza.input.legend

import dev.cannoli.scorza.input.GlyphStyle

class LegendResolver {
    fun resolve(vendorId: Int?, productId: Int?): LegendProfile {
        if (vendorId == BuiltInLegendTable.SONY_VID) {
            return LegendProfile(FaceLayout.STANDARD, GlyphStyle.SHAPES)
        }
        return BuiltInLegendTable.default
    }
}
