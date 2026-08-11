package dev.cannoli.scorza.input.legend

import dev.cannoli.igm.CanonicalButton
import dev.cannoli.scorza.input.GlyphStyle

data class LegendProfile(val faceLayout: FaceLayout, val glyphStyle: GlyphStyle) {
    val menuConfirm: CanonicalButton get() = faceLayout.confirmButton
}
