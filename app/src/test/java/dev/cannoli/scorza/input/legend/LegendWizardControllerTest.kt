package dev.cannoli.scorza.input.legend

import dev.cannoli.igm.CanonicalButton
import dev.cannoli.scorza.input.GlyphStyle
import org.junit.Assert.assertEquals
import org.junit.Test

class LegendWizardControllerTest {
    @Test fun `position-based xbox pad classifies standard redmond`() {
        val c = LegendWizardController()
        c.start(sonyGlyphHint = null)
        assertEquals(WizardStep.PressSouth, c.step.value)
        c.onKeyCaptured(96)
        assertEquals(WizardStep.PressPrimary, c.step.value)
        c.onKeyCaptured(96)
        assertEquals(WizardStep.Done, c.step.value)
        assertEquals(FaceLayout.STANDARD, c.profile()!!.faceLayout)
        assertEquals(GlyphStyle.REDMOND, c.profile()!!.glyphStyle)
        assertEquals(CanonicalButton.BTN_SOUTH, c.profile()!!.menuConfirm)
        val f = c.faceBindings()
        assertEquals(96, f[CanonicalButton.BTN_SOUTH])
        assertEquals(97, f[CanonicalButton.BTN_EAST])
        assertEquals(99, f[CanonicalButton.BTN_WEST])
        assertEquals(100, f[CanonicalButton.BTN_NORTH])
    }

    @Test fun `position-based nintendo pad classifies nintendo plumber confirm east`() {
        val c = LegendWizardController()
        c.start(sonyGlyphHint = null)
        c.onKeyCaptured(96)
        c.onKeyCaptured(97)
        assertEquals(FaceLayout.NINTENDO, c.profile()!!.faceLayout)
        assertEquals(GlyphStyle.PLUMBER, c.profile()!!.glyphStyle)
        assertEquals(CanonicalButton.BTN_EAST, c.profile()!!.menuConfirm)
        val f = c.faceBindings()
        assertEquals(96, f[CanonicalButton.BTN_SOUTH])
        assertEquals(97, f[CanonicalButton.BTN_EAST])
        assertEquals(99, f[CanonicalButton.BTN_WEST])
        assertEquals(100, f[CanonicalButton.BTN_NORTH])
    }

    @Test fun `label-based pad binds captured south and east`() {
        val c = LegendWizardController()
        c.start(sonyGlyphHint = null)
        c.onKeyCaptured(97)
        c.onKeyCaptured(96)
        assertEquals(FaceLayout.NINTENDO, c.profile()!!.faceLayout)
        val f = c.faceBindings()
        assertEquals(97, f[CanonicalButton.BTN_SOUTH])
        assertEquals(96, f[CanonicalButton.BTN_EAST])
        assertEquals(99, f[CanonicalButton.BTN_WEST])
        assertEquals(100, f[CanonicalButton.BTN_NORTH])
    }

    @Test fun `sony hint yields shapes on a bottom-primary pad`() {
        val c = LegendWizardController()
        c.start(sonyGlyphHint = GlyphStyle.SHAPES)
        c.onKeyCaptured(96); c.onKeyCaptured(96)
        assertEquals(GlyphStyle.SHAPES, c.profile()!!.glyphStyle)
        assertEquals(FaceLayout.STANDARD, c.profile()!!.faceLayout)
    }
}
