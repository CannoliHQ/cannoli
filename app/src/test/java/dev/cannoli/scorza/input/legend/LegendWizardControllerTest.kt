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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LegendWizardControllerTest {

    @get:Rule val tmp = TemporaryFolder()

    private val LegendWizardController.step: WizardStep get() = state.value.step

    private fun started(sonyGlyphHint: GlyphStyle? = null) =
        LegendWizardController().apply { start(sonyGlyphHint) }

    // Confirm and back double-pressed, then menu and start captured once each.
    private fun LegendWizardController.answer(
        confirm: Int = 96,
        back: Int = 97,
        menu: Int = 316,
        start: Int = 108,
    ) = apply {
        onKeyCaptured(confirm)
        onKeyCaptured(confirm)
        onKeyCaptured(back)
        onKeyCaptured(back)
        onKeyCaptured(menu)
        onKeyCaptured(start)
    }

    private fun baseMapping() = DeviceMapping(
        id = "test_pad",
        displayName = "Test Pad",
        match = DeviceMatchRule(
            name = "Test Pad",
            vendorId = 1234,
            productId = 5678,
        ),
        bindings = mapOf(
            CanonicalButton.BTN_L to listOf(InputBinding.Button(102)),
            CanonicalButton.BTN_START to listOf(InputBinding.Button(108)),
        ),
        source = MappingSource.ANDROID_DEFAULT,
    )

    private fun DeviceMapping.keyCodeOf(button: CanonicalButton): Int? =
        (bindings[button]?.singleOrNull() as? InputBinding.Button)?.keyCode

    @Test fun `every step advances in order`() {
        val c = started()
        assertEquals(WizardStep.PressConfirm, c.step)
        c.onKeyCaptured(96)
        assertEquals(WizardStep.ConfirmAgain, c.step)
        c.onKeyCaptured(96)
        assertEquals(WizardStep.PressBack, c.step)
        c.onKeyCaptured(97)
        assertEquals(WizardStep.BackAgain, c.step)
        c.onKeyCaptured(97)
        assertEquals(WizardStep.PressMenu, c.step)
        c.onKeyCaptured(316)
        assertEquals(WizardStep.PressStart, c.step)
        c.onKeyCaptured(108)
        assertEquals(WizardStep.Done, c.step)
    }

    @Test fun `presses after done are ignored`() {
        val c = started().answer()
        c.onKeyCaptured(99)
        assertEquals(WizardStep.Done, c.step)
        assertEquals(316, c.buildMapping(baseMapping()).keyCodeOf(CanonicalButton.BTN_MENU))
    }

    @Test fun `a mismatched second confirm discards the first capture and re-asks`() {
        val c = started()
        c.onKeyCaptured(96)
        c.onKeyCaptured(97)
        assertEquals(WizardStep.PressConfirm, c.step)
        assertEquals(WizardNotice.PressesDidNotMatch, c.state.value.notice)
        assertNull(c.profile())

        c.onKeyCaptured(97)
        assertNull(c.state.value.notice)
        c.onKeyCaptured(97)
        assertEquals(WizardStep.PressBack, c.step)
        assertNull(c.state.value.notice)
        // The discarded 96 would have classified as STANDARD.
        assertEquals(FaceLayout.NINTENDO, c.profile()!!.faceLayout)
    }

    @Test fun `back is rejected when it is the button confirm already took`() {
        val c = started()
        c.onKeyCaptured(96)
        c.onKeyCaptured(96)
        c.onKeyCaptured(96)
        assertEquals(WizardStep.PressBack, c.step)
        assertEquals(WizardNotice.BackMustDifferFromConfirm, c.state.value.notice)

        c.onKeyCaptured(97)
        assertEquals(WizardStep.BackAgain, c.step)
        assertNull(c.state.value.notice)
    }

    @Test fun `confirm arriving at the second back press re-asks with the same reason`() {
        val c = started()
        c.onKeyCaptured(96)
        c.onKeyCaptured(96)
        c.onKeyCaptured(97)
        c.onKeyCaptured(96)
        assertEquals(WizardStep.PressBack, c.step)
        assertEquals(WizardNotice.BackMustDifferFromConfirm, c.state.value.notice)
        assertEquals(1, c.state.value.capturesDone)
    }

    @Test fun `the mismatch notice clears when the wizard restarts or runs to the end`() {
        val c = started()
        c.onKeyCaptured(96)
        c.onKeyCaptured(97)
        assertEquals(WizardNotice.PressesDidNotMatch, c.state.value.notice)
        c.start(sonyGlyphHint = null)
        assertNull(c.state.value.notice)

        c.answer()
        assertNull(c.state.value.notice)
    }

    @Test fun `a mismatched second back discards the first capture and re-asks`() {
        val c = started()
        c.onKeyCaptured(96)
        c.onKeyCaptured(96)
        c.onKeyCaptured(97)
        c.onKeyCaptured(99)
        assertEquals(WizardStep.PressBack, c.step)
        assertEquals(WizardNotice.PressesDidNotMatch, c.state.value.notice)

        c.onKeyCaptured(99)
        assertNull(c.state.value.notice)
        c.onKeyCaptured(99)
        assertEquals(WizardStep.PressMenu, c.step)
        c.onKeyCaptured(316)
        c.onKeyCaptured(108)
        val mapping = c.buildMapping(baseMapping())
        assertEquals(99, mapping.keyCodeOf(CanonicalButton.BTN_WEST))
        assertEquals(CanonicalButton.BTN_WEST, mapping.menuBack)
    }

    // Four questions, four pips: the confirming second press of confirm and of back is part of the
    // question it settles, not a capture of its own.
    @Test fun `captures completed track the four questions`() {
        val c = started()
        assertEquals(0, c.state.value.capturesDone)
        c.onKeyCaptured(96)
        assertEquals(0, c.state.value.capturesDone)
        c.onKeyCaptured(96)
        assertEquals(1, c.state.value.capturesDone)
        c.onKeyCaptured(97)
        assertEquals(1, c.state.value.capturesDone)
        c.onKeyCaptured(97)
        assertEquals(2, c.state.value.capturesDone)
        c.onKeyCaptured(316)
        assertEquals(3, c.state.value.capturesDone)
        c.onKeyCaptured(108)
        assertEquals(WIZARD_CAPTURES, c.state.value.capturesDone)
    }

    // A question being re-asked costs no pip: both of its steps sit at the same count, so a
    // rejected press never takes back a capture the user already made.
    @Test fun `a re-asked question keeps the captures already made`() {
        val c = started()
        c.onKeyCaptured(96)
        c.onKeyCaptured(96)
        assertEquals(1, c.state.value.capturesDone)
        c.onKeyCaptured(97)
        c.onKeyCaptured(99)
        assertEquals(WizardStep.PressBack, c.step)
        assertEquals(1, c.state.value.capturesDone)
    }

    @Test fun `start resets a wizard that was part way through`() {
        val c = started()
        c.onKeyCaptured(96)
        c.onKeyCaptured(96)
        c.start(sonyGlyphHint = null)
        assertEquals(WizardStep.PressConfirm, c.step)
        assertNull(c.profile())
        assertEquals(baseMapping(), c.buildMapping(baseMapping()))
    }

    @Test fun `buildMapping on a standard pad binds confirm south and back east`() {
        val mapping = started().answer(confirm = 96, back = 97).buildMapping(baseMapping())

        assertEquals(96, mapping.keyCodeOf(CanonicalButton.BTN_SOUTH))
        assertEquals(97, mapping.keyCodeOf(CanonicalButton.BTN_EAST))
        assertEquals(CanonicalButton.BTN_SOUTH, mapping.menuConfirm)
        assertEquals(CanonicalButton.BTN_EAST, mapping.menuBack)
        assertEquals(GlyphStyle.REDMOND, mapping.glyphStyle)
        assertEquals(316, mapping.keyCodeOf(CanonicalButton.BTN_MENU))
        assertEquals(108, mapping.keyCodeOf(CanonicalButton.BTN_START))
        assertTrue(mapping.userEdited)
        assertEquals(MappingSource.USER_WIZARD, mapping.source)
        assertEquals(listOf(InputBinding.Button(102)), mapping.bindings[CanonicalButton.BTN_L])
    }

    @Test fun `buildMapping on a nintendo pad binds confirm east and back south`() {
        val mapping = started().answer(confirm = 97, back = 96).buildMapping(baseMapping())

        assertEquals(97, mapping.keyCodeOf(CanonicalButton.BTN_EAST))
        assertEquals(96, mapping.keyCodeOf(CanonicalButton.BTN_SOUTH))
        assertEquals(CanonicalButton.BTN_EAST, mapping.menuConfirm)
        assertEquals(CanonicalButton.BTN_SOUTH, mapping.menuBack)
        assertEquals(GlyphStyle.PLUMBER, mapping.glyphStyle)
    }

    @Test fun `buildMapping on a non-standard-keycode pad falls back to south and east`() {
        val mapping = started().answer(confirm = 200, back = 201).buildMapping(baseMapping())

        assertEquals(200, mapping.keyCodeOf(CanonicalButton.BTN_SOUTH))
        assertEquals(201, mapping.keyCodeOf(CanonicalButton.BTN_EAST))
        assertEquals(CanonicalButton.BTN_SOUTH, mapping.menuConfirm)
        assertEquals(CanonicalButton.BTN_EAST, mapping.menuBack)
        assertEquals(GlyphStyle.REDMOND, mapping.glyphStyle)
    }

    @Test fun `a non-standard back keycode takes the face slot confirm left`() {
        val mapping = started().answer(confirm = 97, back = 4).buildMapping(baseMapping())

        assertEquals(4, mapping.keyCodeOf(CanonicalButton.BTN_SOUTH))
        assertEquals(CanonicalButton.BTN_SOUTH, mapping.menuBack)
    }

    // All four captures are mandatory, so the captured menu and start always replace what the base
    // mapping guessed rather than being left alone.
    @Test fun `captured menu and start replace the base bindings`() {
        val mapping = started().answer(menu = 316, start = 109).buildMapping(baseMapping())

        assertEquals(316, mapping.keyCodeOf(CanonicalButton.BTN_MENU))
        assertEquals(109, mapping.keyCodeOf(CanonicalButton.BTN_START))
    }

    @Test fun `the sony hint yields shapes on a bottom-confirm pad`() {
        val c = started(sonyGlyphHint = GlyphStyle.SHAPES).answer(confirm = 96, back = 97)
        assertEquals(GlyphStyle.SHAPES, c.profile()!!.glyphStyle)
        assertEquals(FaceLayout.STANDARD, c.profile()!!.faceLayout)
        assertEquals(GlyphStyle.SHAPES, c.buildMapping(baseMapping()).glyphStyle)
    }

    @Test fun `buildMapping returns the base until every capture is in`() {
        val c = started()
        val base = baseMapping()
        assertEquals(base, c.buildMapping(base))
        c.onKeyCaptured(96)
        assertEquals(base, c.buildMapping(base))
        c.onKeyCaptured(96)
        assertEquals(base, c.buildMapping(base))
        c.onKeyCaptured(97)
        c.onKeyCaptured(97)
        assertEquals(base, c.buildMapping(base))
        c.onKeyCaptured(316)
        assertEquals(base, c.buildMapping(base))
    }

    @Test fun `confirmKeyCode reports the press that established the pad`() {
        val c = started()
        assertNull(c.confirmKeyCode())
        c.answer(confirm = 97, back = 96)
        assertEquals(97, c.confirmKeyCode())
    }

    @Test fun `buildMapping result round-trips through the autoconfig repository`() {
        val mapping = started().answer(confirm = 97, back = 96).buildMapping(baseMapping())

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
        assertEquals(CanonicalButton.BTN_SOUTH, reloaded.menuBack)
        assertEquals(97, reloaded.keyCodeOf(CanonicalButton.BTN_EAST))
        assertEquals(96, reloaded.keyCodeOf(CanonicalButton.BTN_SOUTH))
    }
}
