package dev.cannoli.scorza.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.cannoli.scorza.input.CanonicalButton
import dev.cannoli.scorza.input.GlyphStyle
import dev.cannoli.scorza.input.runtime.labelSet
import dev.cannoli.ui.ButtonLabelSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SegaGlyphLabelTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun `the four faces read by printed letter`() {
        val set = ButtonLabelSet.HEDGEHOG_6
        assertEquals("A", set.east)
        assertEquals("B", set.south)
        assertEquals("X", set.north)
        assertEquals("Y", set.west)
    }

    @Test fun `C and Z sit on the stick clicks`() {
        assertEquals("C", ButtonLabelSet.HEDGEHOG_6.l3)
        assertEquals("Z", ButtonLabelSet.HEDGEHOG_6.r3)
        assertNull(ButtonLabelSet.PLUMBER.l3)
        assertNull(ButtonLabelSet.REDMOND.r3)
    }

    @Test fun `the launcher names L3 and R3 as C and Z`() {
        assertEquals("C", canonicalLabel(context, CanonicalButton.BTN_L3, GlyphStyle.HEDGEHOG_6))
        assertEquals("Z", canonicalLabel(context, CanonicalButton.BTN_R3, GlyphStyle.HEDGEHOG_6))
        assertEquals("L3", canonicalLabel(context, CanonicalButton.BTN_L3, GlyphStyle.PLUMBER))
    }

    @Test fun `the style has a display name`() {
        assertEquals("Sega 6-Button", glyphStyleName(context, GlyphStyle.HEDGEHOG_6))
    }

    @Test fun `the style survives the active mapping handoff`() {
        val mapping = dev.cannoli.scorza.input.DeviceMapping(
            id = "sega",
            displayName = "Retroid Pocket Classic",
            match = dev.cannoli.scorza.input.DeviceMatchRule(),
            bindings = emptyMap(),
            glyphStyle = GlyphStyle.HEDGEHOG_6,
            source = dev.cannoli.scorza.input.MappingSource.USER_WIZARD,
            userEdited = true,
        )
        assertEquals(ButtonLabelSet.HEDGEHOG_6, mapping.labelSet(ButtonLabelSet.PLUMBER))
    }
}
