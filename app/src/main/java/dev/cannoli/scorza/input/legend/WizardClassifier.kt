package dev.cannoli.scorza.input.legend

import dev.cannoli.scorza.input.GlyphStyle

// Confirm on the east position means the A label sits right, which is the Nintendo shell. Any other
// keycode, standard or not, leaves the A label at the bottom.
fun classify(confirmKeyCode: Int, sonyGlyphHint: GlyphStyle?): LegendProfile {
    return if (confirmKeyCode == KEYCODE_BUTTON_B) {
        LegendProfile(FaceLayout.NINTENDO, GlyphStyle.PLUMBER)
    } else {
        LegendProfile(FaceLayout.STANDARD, sonyGlyphHint ?: GlyphStyle.REDMOND)
    }
}

private const val KEYCODE_BUTTON_B = 97
