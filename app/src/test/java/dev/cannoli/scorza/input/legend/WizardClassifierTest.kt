package dev.cannoli.scorza.input.legend

import dev.cannoli.scorza.input.GlyphStyle
import org.junit.Assert.assertEquals
import org.junit.Test

class WizardClassifierTest {
    @Test fun `same button, no sony hint means standard redmond`() {
        assertEquals(
            LegendProfile(FaceLayout.STANDARD, GlyphStyle.REDMOND),
            classify(bottomKeyCode = 96, primaryKeyCode = 96, sonyGlyphHint = null),
        )
    }
    @Test fun `same button with sony hint means playstation shapes`() {
        assertEquals(
            LegendProfile(FaceLayout.STANDARD, GlyphStyle.SHAPES),
            classify(96, 96, sonyGlyphHint = GlyphStyle.SHAPES),
        )
    }
    @Test fun `different buttons means nintendo plumber, sony hint ignored`() {
        assertEquals(
            LegendProfile(FaceLayout.NINTENDO, GlyphStyle.PLUMBER),
            classify(bottomKeyCode = 97, primaryKeyCode = 96, sonyGlyphHint = GlyphStyle.SHAPES),
        )
    }
}
