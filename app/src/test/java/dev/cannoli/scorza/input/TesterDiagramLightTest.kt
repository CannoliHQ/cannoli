package dev.cannoli.scorza.input

import android.view.KeyEvent
import dev.cannoli.ui.ButtonLabelSet
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TesterDiagramLightTest {

    @Test fun `C and Z light nothing under PLUMBER, REDMOND or SHAPES`() {
        for (labelSet in listOf(ButtonLabelSet.PLUMBER, ButtonLabelSet.REDMOND, ButtonLabelSet.SHAPES)) {
            assertFalse(testerLightsDiagram(KeyEvent.KEYCODE_BUTTON_C, labelSet))
            assertFalse(testerLightsDiagram(KeyEvent.KEYCODE_BUTTON_Z, labelSet))
        }
    }

    @Test fun `C and Z light the face buttons under HEDGEHOG_6`() {
        assertTrue(testerLightsDiagram(KeyEvent.KEYCODE_BUTTON_C, ButtonLabelSet.HEDGEHOG_6))
        assertTrue(testerLightsDiagram(KeyEvent.KEYCODE_BUTTON_Z, ButtonLabelSet.HEDGEHOG_6))
    }

    @Test fun `a real stick click keeps lighting its stick`() {
        assertTrue(testerLightsDiagram(KeyEvent.KEYCODE_BUTTON_THUMBL, ButtonLabelSet.PLUMBER))
        assertTrue(testerLightsDiagram(KeyEvent.KEYCODE_BUTTON_THUMBR, ButtonLabelSet.PLUMBER))
    }

    @Test fun `an unrelated button keeps lighting`() {
        assertTrue(testerLightsDiagram(KeyEvent.KEYCODE_BUTTON_A, ButtonLabelSet.PLUMBER))
    }
}
