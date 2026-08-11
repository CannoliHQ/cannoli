package dev.cannoli.scorza.input.legend

import dev.cannoli.scorza.input.GlyphStyle

fun classify(bottomKeyCode: Int, primaryKeyCode: Int, sonyGlyphHint: GlyphStyle?): LegendProfile {
    return if (bottomKeyCode == primaryKeyCode) {
        LegendProfile(FaceLayout.STANDARD, sonyGlyphHint ?: GlyphStyle.REDMOND)
    } else {
        LegendProfile(FaceLayout.NINTENDO, GlyphStyle.PLUMBER)
    }
}
