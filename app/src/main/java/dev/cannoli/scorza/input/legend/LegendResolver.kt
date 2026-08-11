package dev.cannoli.scorza.input.legend

import dev.cannoli.scorza.input.GlyphStyle

class LegendResolver {
    fun resolve(vendorId: Int?, productId: Int?, buildModel: String?): LegendProfile {
        val model = buildModel.orEmpty()
        BuiltInLegendTable.byBuildModelPrefix.firstOrNull {
            model.startsWith(it.first, ignoreCase = true)
        }?.let { return it.second }

        if (vendorId != null && productId != null) {
            BuiltInLegendTable.byVidPid[vendorId to productId]?.let { return it }
        }

        if (vendorId == BuiltInLegendTable.SONY_VID) {
            return LegendProfile(FaceLayout.STANDARD, GlyphStyle.SHAPES)
        }
        return BuiltInLegendTable.default
    }
}
