package dev.cannoli.scorza.input.legend

import dev.cannoli.igm.CanonicalButton
import dev.cannoli.scorza.input.ConnectedDevice
import dev.cannoli.scorza.input.DeviceMapping
import dev.cannoli.scorza.input.DeviceMatchRule
import dev.cannoli.scorza.input.GlyphStyle
import dev.cannoli.scorza.input.InputBinding
import dev.cannoli.scorza.input.MappingSource
import dev.cannoli.scorza.input.autoconfig.AutoconfigRepository
import dev.cannoli.scorza.input.resolver.RetroArchAutoconfigImporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LegendWizardControllerTest {

    @get:Rule val tmp = TemporaryFolder()
    @Test fun `position-based xbox pad classifies standard redmond`() {
        val c = LegendWizardController()
        c.start(sonyGlyphHint = null)
        assertEquals(WizardStep.PressSouth, c.step.value)
        c.onKeyCaptured(96)
        assertEquals(WizardStep.PressPrimary, c.step.value)
        c.onKeyCaptured(96)
        assertEquals(WizardStep.PressMenu, c.step.value)
        c.onKeyCaptured(316)
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

    private fun baseMapping() = DeviceMapping(
        id = "test_pad",
        displayName = "Test Pad",
        match = DeviceMatchRule(
            name = "Test Pad",
            vendorId = 1234,
            productId = 5678,
            descriptor = "abc123",
        ),
        bindings = mapOf(
            CanonicalButton.BTN_L to listOf(InputBinding.Button(102)),
        ),
        source = MappingSource.ANDROID_DEFAULT,
    )

    @Test fun `buildMapping writes captured face slots and preserves non-face bindings`() {
        val c = LegendWizardController()
        c.start(sonyGlyphHint = null)
        c.onKeyCaptured(96)
        c.onKeyCaptured(97)
        c.onKeyCaptured(316)
        val base = baseMapping()

        val mapping = c.buildMapping(base)

        assertEquals(
            InputBinding.Button(96),
            mapping.bindings[CanonicalButton.BTN_SOUTH]!!.single(),
        )
        assertEquals(
            InputBinding.Button(97),
            mapping.bindings[CanonicalButton.BTN_EAST]!!.single(),
        )
        assertEquals(GlyphStyle.PLUMBER, mapping.glyphStyle)
        assertEquals(CanonicalButton.BTN_EAST, mapping.menuConfirm)
        assertEquals(CanonicalButton.BTN_SOUTH, mapping.menuBack)
        assertTrue(mapping.userEdited)
        assertEquals(MappingSource.USER_WIZARD, mapping.source)
        assertEquals(
            listOf(InputBinding.Button(102)),
            mapping.bindings[CanonicalButton.BTN_L],
        )
        assertEquals(
            listOf(InputBinding.Button(316)),
            mapping.bindings[CanonicalButton.BTN_MENU],
        )
    }

    @Test fun `buildMapping result round-trips through the autoconfig repository`() {
        val c = LegendWizardController()
        c.start(sonyGlyphHint = null)
        c.onKeyCaptured(96)
        c.onKeyCaptured(97)
        val mapping = c.buildMapping(baseMapping())

        val repo = AutoconfigRepository { tmp.root }
        repo.save(mapping)
        val entry = repo.listEntries().single()
        val device = ConnectedDevice(
            androidDeviceId = 1,
            descriptor = "abc123",
            name = "Test Pad",
            vendorId = 1234,
            productId = 5678,
            androidBuildModel = "TestModel",
            sourceMask = 0,
            connectedAtMillis = 0L,
        )

        val reloaded = RetroArchAutoconfigImporter.import(entry, device)

        assertEquals(GlyphStyle.PLUMBER, reloaded.glyphStyle)
        assertEquals(CanonicalButton.BTN_EAST, reloaded.menuConfirm)
        assertEquals(
            InputBinding.Button(96),
            reloaded.bindings[CanonicalButton.BTN_SOUTH]!!.single(),
        )
    }
}
