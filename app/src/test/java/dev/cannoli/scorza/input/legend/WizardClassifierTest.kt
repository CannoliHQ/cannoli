package dev.cannoli.scorza.input.legend

import dev.cannoli.scorza.input.GlyphStyle
import org.junit.Assert.assertEquals
import org.junit.Test

class WizardClassifierTest {
    @Test fun `confirm on the bottom face code means standard redmond`() {
        assertEquals(
            LegendProfile(FaceLayout.STANDARD, GlyphStyle.REDMOND),
            classify(confirmKeyCode = 96, sonyGlyphHint = null),
        )
    }

    @Test fun `confirm on the bottom face code with the sony hint means shapes`() {
        assertEquals(
            LegendProfile(FaceLayout.STANDARD, GlyphStyle.SHAPES),
            classify(confirmKeyCode = 96, sonyGlyphHint = GlyphStyle.SHAPES),
        )
    }

    @Test fun `confirm on the east face code means nintendo plumber, sony hint ignored`() {
        assertEquals(
            LegendProfile(FaceLayout.NINTENDO, GlyphStyle.PLUMBER),
            classify(confirmKeyCode = 97, sonyGlyphHint = GlyphStyle.SHAPES),
        )
    }

    @Test fun `confirm on another face code stays standard`() {
        assertEquals(
            LegendProfile(FaceLayout.STANDARD, GlyphStyle.REDMOND),
            classify(confirmKeyCode = 99, sonyGlyphHint = null),
        )
    }

    @Test fun `a non-standard keycode classifies as standard rather than throwing`() {
        assertEquals(
            LegendProfile(FaceLayout.STANDARD, GlyphStyle.REDMOND),
            classify(confirmKeyCode = 200, sonyGlyphHint = null),
        )
    }
}
