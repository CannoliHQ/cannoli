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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LegendWizardControllerTest {

    @get:Rule val tmp = TemporaryFolder()

    private val LegendWizardController.step: WizardStep get() = state.value.step

    // No confirm supplied, so the wizard asks the same three-press question the welcome step does.
    private fun started() = LegendWizardController().apply { start(null) }

    // Arriving from the welcome run, which has already settled confirm.
    private fun startedFromWelcome(confirm: Int = 96) =
        LegendWizardController().apply { start(confirm) }

    private fun LegendWizardController.confirmRun(keyCode: Int = 96) = apply {
        repeat(CONFIRM_PRESSES_REQUIRED) { onKeyCaptured(keyCode) }
    }

    // Confirm and back double-pressed, then menu and start captured once each.
    // Skips every question after phase one, which is how a pad missing those buttons finishes.
    private fun LegendWizardController.finishRemaining() = apply {
        var guard = 0
        while (state.value.step != WizardStep.Done && guard++ < 64) {
            val s = state.value
            when {
                s.step == WizardStep.Appearance -> onAppearanceChosen(GlyphStyle.REDMOND)
                s.capturing in REQUIRED_CAPTURES ->
                    onButtonCaptured(listOf(InputBinding.Button(900 + guard)))
                else -> skip()
            }
        }
    }

    private fun LegendWizardController.answer(
        confirm: Int = 96,
        back: Int = 97,
        menu: Int = 316,
        start: Int = 108,
    ) = apply {
        confirmRun(confirm)
        onKeyCaptured(back)
        onKeyCaptured(back)
        onKeyCaptured(start)
        onKeyCaptured(menu)
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
        source = MappingSource.UNIDENTIFIED,
    )

    private fun DeviceMapping.keyCodeOf(button: CanonicalButton): Int? =
        (bindings[button]?.singleOrNull() as? InputBinding.Button)?.keyCode

    @Test fun `every step advances in order`() {
        val c = started()
        assertEquals(WizardStep.PressConfirm, c.step)
        c.confirmRun(96)
        assertEquals(WizardStep.PressBack, c.step)
        c.onKeyCaptured(97)
        assertEquals(WizardStep.BackAgain, c.step)
        c.onKeyCaptured(97)
        assertEquals(WizardStep.PressStart, c.step)
        c.onKeyCaptured(108)
        assertEquals(WizardStep.PressMenu, c.step)
        c.onKeyCaptured(316)
        // Phase one no longer ends the wizard: it runs on into the D-pad, which is what makes the
        // appearance list operable and the pad navigable if everything after it is skipped.
        assertEquals(WizardStep.Capture, c.step)
        assertEquals(CanonicalButton.BTN_UP, c.state.value.capturing)
    }

    @Test fun `presses after done are ignored`() {
        val c = started().answer().finishRemaining()
        c.onKeyCaptured(99)
        assertEquals(WizardStep.Done, c.step)
        assertEquals(316, c.buildMapping(baseMapping()).keyCodeOf(CanonicalButton.BTN_MENU))
    }

    @Test fun `a press of another button restarts the confirm run rather than failing it`() {
        val c = started()
        // A different button empties the run and begins its own, so two of one and then three of
        // another still settles on the second.
        c.onKeyCaptured(96)
        c.onKeyCaptured(96)
        c.confirmRun(97)
        assertEquals(WizardStep.PressBack, c.step)
        assertEquals(97, c.confirmKeyCode())
    }

    @Test fun `the wizard does not ask for confirm when the welcome run already settled it`() {
        val c = startedFromWelcome(confirm = 96)
        assertEquals(WizardStep.PressBack, c.step)
        assertEquals(96, c.confirmKeyCode())
    }

    @Test fun `back is rejected when it is the button confirm already took`() {
        val c = started()
        c.confirmRun(96)
        c.onKeyCaptured(96)
        assertEquals(WizardStep.PressBack, c.step)
        assertEquals(WizardNotice.BackMustDifferFromConfirm, c.state.value.notice)

        c.onKeyCaptured(97)
        assertEquals(WizardStep.BackAgain, c.step)
        assertNull(c.state.value.notice)
    }

    @Test fun `confirm arriving at the second back press re-asks with the same reason`() {
        val c = started()
        c.confirmRun(96)
        c.onKeyCaptured(97)
        c.onKeyCaptured(96)
        assertEquals(WizardStep.PressBack, c.step)
        assertEquals(WizardNotice.BackMustDifferFromConfirm, c.state.value.notice)
    }

    @Test fun `the mismatch notice clears when the wizard restarts or runs to the end`() {
        val c = started()
        c.confirmRun(96)
        c.onKeyCaptured(96)
        assertEquals(WizardNotice.BackMustDifferFromConfirm, c.state.value.notice)
        c.start(null)
        assertNull(c.state.value.notice)

        c.answer()
        assertNull(c.state.value.notice)
    }

    @Test fun `a mismatched second back discards the first capture and re-asks`() {
        val c = started()
        c.confirmRun(96)
        c.onKeyCaptured(97)
        c.onKeyCaptured(99)
        assertEquals(WizardStep.PressBack, c.step)
        assertEquals(WizardNotice.PressesDidNotMatch, c.state.value.notice)

        c.onKeyCaptured(99)
        assertNull(c.state.value.notice)
        c.onKeyCaptured(99)
        assertEquals(WizardStep.PressStart, c.step)
        c.onKeyCaptured(316)
        c.onKeyCaptured(108)
        val mapping = c.buildMapping(baseMapping())
        assertEquals(99, mapping.keyCodeOf(CanonicalButton.BTN_WEST))
        assertEquals(CanonicalButton.BTN_WEST, mapping.menuBack)
    }

    // The confirm run is the only question answered by more than one press, so it is the only one
    // that draws progress. A press with no visible effect there reads as a press that went missing.
    @Test fun `the confirm run reports its progress so the presses are visibly counted`() {
        val c = started()
        assertEquals(0, c.state.value.confirmRunCount)
        c.onKeyCaptured(96)
        assertEquals(1, c.state.value.confirmRunCount)
        c.onKeyCaptured(96)
        assertEquals(2, c.state.value.confirmRunCount)
        c.onKeyCaptured(96)
        assertEquals(WizardStep.PressBack, c.step)
    }

    @Test fun `a press of a different button restarts the count rather than adding to it`() {
        val c = started()
        c.onKeyCaptured(96)
        c.onKeyCaptured(96)
        c.onKeyCaptured(97)
        assertEquals(1, c.state.value.confirmRunCount)
    }

    // A rejected press re-asks the question it failed, and never takes back an answer already given.
    @Test fun `a re-asked question keeps the answers already made`() {
        val c = started()
        c.confirmRun(96)
        c.onKeyCaptured(97)
        c.onKeyCaptured(99)
        assertEquals(WizardStep.PressBack, c.step)
        assertEquals(96, c.confirmKeyCode())
    }

    @Test fun `start resets a wizard that was part way through`() {
        val c = started()
        c.confirmRun(96)
        c.onKeyCaptured(97)
        c.start(null)
        assertEquals(WizardStep.PressConfirm, c.step)
        assertEquals(baseMapping(), c.buildMapping(baseMapping()))
    }

    @Test fun `buildMapping on a standard pad binds confirm south and back east`() {
        val mapping = started().answer(confirm = 96, back = 97).buildMapping(baseMapping())

        assertEquals(96, mapping.keyCodeOf(CanonicalButton.BTN_SOUTH))
        assertEquals(97, mapping.keyCodeOf(CanonicalButton.BTN_EAST))
        assertEquals(CanonicalButton.BTN_SOUTH, mapping.menuConfirm)
        assertEquals(CanonicalButton.BTN_EAST, mapping.menuBack)
        assertEquals(baseMapping().glyphStyle, mapping.glyphStyle)
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
        assertEquals(baseMapping().glyphStyle, mapping.glyphStyle)
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

    @Test fun `an unanswered glyph question leaves the base style rather than guessing one`() {
        // Nothing is inferred from the confirm keycode any more: the style is the user's answer or
        // whatever the pad already had.
        val c = started().answer(confirm = 96, back = 97)
        assertEquals(baseMapping().glyphStyle, c.buildMapping(baseMapping()).glyphStyle)
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

    private fun LegendWizardController.completeRequiredActions(
        confirm: Int = 96, back: Int = 97, menu: Int = 82, startBtn: Int = 108,
    ) {
        start(confirm)
        onKeyCaptured(back); onKeyCaptured(back)
        onKeyCaptured(startBtn)
        onKeyCaptured(menu)
    }

    @Test
    fun `phase one runs into the D-pad, which the appearance list needs to be operable`() {
        val c = LegendWizardController()
        c.completeRequiredActions()
        assertEquals(WizardStep.Capture, c.state.value.step)
        assertEquals(CanonicalButton.BTN_UP, c.state.value.capturing)
    }

    @Test
    fun `the appearance question comes after the D-pad and before the remaining faces`() {
        val c = LegendWizardController()
        c.completeRequiredActions()
        repeat(4) { c.onButtonCaptured(listOf(InputBinding.Button(19 + it))) }
        assertEquals(WizardStep.Appearance, c.state.value.step)
        c.onAppearanceChosen(GlyphStyle.SHAPES)
        // Confirm took south and back took east, so the two left are north and west.
        assertEquals(CanonicalButton.BTN_NORTH, c.state.value.capturing)
    }

    @Test
    fun `a skipped button is left unbound rather than guessed`() {
        val c = LegendWizardController()
        c.completeRequiredActions()
        repeat(4) { c.onButtonCaptured(listOf(InputBinding.Button(19 + it))) }
        c.onAppearanceChosen(GlyphStyle.REDMOND)
        c.skip()
        val built = c.buildMapping(baseMapping())
        assertTrue(built.bindings[CanonicalButton.BTN_NORTH].isNullOrEmpty())
    }

    @Test
    fun `start skips and back undoes, and neither is taken as the answer`() {
        val c = LegendWizardController()
        c.completeRequiredActions(startBtn = 108, back = 97)
        // The D-pad is required, so walk past it before skip means anything.
        repeat(4) { c.onButtonCaptured(listOf(InputBinding.Button(19 + it))) }
        c.onAppearanceChosen(GlyphStyle.REDMOND)
        val firstFace = c.state.value.capturing
        assertTrue(c.consumeControlPress(108))
        assertTrue(c.state.value.capturing != firstFace)
        assertTrue(c.consumeControlPress(97))
        assertEquals(firstFace, c.state.value.capturing)
    }

    @Test
    fun `undo clears the answer it steps back to, so a mistake is asked again`() {
        val c = LegendWizardController()
        c.completeRequiredActions()
        c.onButtonCaptured(listOf(InputBinding.Button(19)))
        c.onButtonCaptured(listOf(InputBinding.Button(20)))
        c.undo()
        c.undo()
        assertEquals(CanonicalButton.BTN_UP, c.state.value.capturing)
        c.onButtonCaptured(listOf(InputBinding.Button(21)))
        c.onButtonCaptured(listOf(InputBinding.Button(22)))
        c.onButtonCaptured(listOf(InputBinding.Button(23)))
        c.onButtonCaptured(listOf(InputBinding.Button(24)))
        c.onAppearanceChosen(GlyphStyle.REDMOND)
        val built = c.buildMapping(baseMapping())
        assertEquals(listOf(InputBinding.Button(21)), built.bindings[CanonicalButton.BTN_UP])
    }

    @Test
    fun `undo at the first question returns to menu, the last action asked before it`() {
        val c = LegendWizardController()
        c.completeRequiredActions()
        c.undo()
        assertEquals(WizardStep.PressMenu, c.state.value.step)
    }

    @Test
    fun `undo is refused before back is a known button`() {
        val c = LegendWizardController()
        c.start(null)
        assertTrue(!c.state.value.canUndo)
        c.confirmRun(96)
        assertTrue(!c.state.value.canUndo)
        c.onKeyCaptured(97); c.onKeyCaptured(97)
        assertTrue(c.state.value.canUndo)
    }

    @Test
    fun `the chosen appearance wins over anything inferred`() {
        val c = LegendWizardController()
        c.completeRequiredActions()
        repeat(4) { c.onButtonCaptured(listOf(InputBinding.Button(19 + it))) }
        c.onAppearanceChosen(GlyphStyle.SHAPES)
        assertEquals(GlyphStyle.SHAPES, c.buildMapping(baseMapping()).glyphStyle)
    }

    @Test
    fun `the appearance list is worked with the D-pad just captured`() {
        val c = LegendWizardController()
        c.completeRequiredActions(confirm = 96)
        c.onButtonCaptured(listOf(InputBinding.Button(19)))
        c.onButtonCaptured(listOf(InputBinding.Button(20)))
        c.onButtonCaptured(listOf(InputBinding.Button(21)))
        c.onButtonCaptured(listOf(InputBinding.Button(22)))
        assertEquals(0, c.state.value.appearanceIndex)
        c.onKeyCaptured(20)
        assertEquals(1, c.state.value.appearanceIndex)
        c.onKeyCaptured(19)
        assertEquals(0, c.state.value.appearanceIndex)
        c.onKeyCaptured(96)
        assertEquals(GlyphStyle.REDMOND, c.buildMapping(baseMapping()).glyphStyle)
    }

    @Test
    fun `a D-pad reported as a hat is captured, not only one sent as keycodes`() {
        val c = LegendWizardController()
        c.completeRequiredActions()
        // AXIS_HAT_Y at full deflection is how most pads report up.
        c.captureRawAxisEvent(mapOf(16 to -1f))
        c.tickCapture()
        assertEquals(CanonicalButton.BTN_UP, c.state.value.capturing)
        Thread.sleep(dev.cannoli.scorza.input.BindingCapture.CAPTURE_WINDOW_MS + 20)
        c.tickCapture()
        assertEquals(CanonicalButton.BTN_DOWN, c.state.value.capturing)
    }

    @Test
    fun `a key press moves the wizard on once it settles`() {
        val c = LegendWizardController()
        c.completeRequiredActions()
        c.onKeyCaptured(19)
        assertEquals(CanonicalButton.BTN_UP, c.state.value.capturing)
        Thread.sleep(dev.cannoli.scorza.input.BindingCapture.CAPTURE_WINDOW_MS + 20)
        c.tickCapture()
        assertEquals(CanonicalButton.BTN_DOWN, c.state.value.capturing)
    }

    @Test
    fun `skip is never captured as the binding it was meant to pass over`() {
        val c = LegendWizardController()
        c.completeRequiredActions(startBtn = 108)
        repeat(4) { c.onKeyCaptured(19 + it); Thread.sleep(dev.cannoli.scorza.input.BindingCapture.CAPTURE_WINDOW_MS + 20); c.tickCapture() }
        c.onAppearanceChosen(GlyphStyle.REDMOND)
        val face = c.state.value.capturing!!
        c.onKeyCaptured(108)
        Thread.sleep(dev.cannoli.scorza.input.BindingCapture.CAPTURE_WINDOW_MS + 20)
        c.tickCapture()
        val built = c.buildMapping(baseMapping())
        assertTrue(built.bindings[face].isNullOrEmpty())
    }

    @Test
    fun `a stick is captured as the bipolar pair the importer produces`() {
        val c = LegendWizardController()
        c.completeRequiredActions()
        repeat(4) { c.onKeyCaptured(19 + it); Thread.sleep(dev.cannoli.scorza.input.BindingCapture.CAPTURE_WINDOW_MS + 20); c.tickCapture() }
        c.onAppearanceChosen(GlyphStyle.REDMOND)
        var guard = 0
        while (c.state.value.capturing != CanonicalButton.BTN_LSTICK_X && guard++ < 20) c.skip()
        assertEquals(CanonicalButton.BTN_LSTICK_X, c.state.value.capturing)
        c.captureRawAxisEvent(mapOf(0 to 1f))
        Thread.sleep(dev.cannoli.scorza.input.BindingCapture.CAPTURE_WINDOW_MS + 20)
        c.tickCapture()
        val axes = c.buildMapping(baseMapping()).bindings[CanonicalButton.BTN_LSTICK_X]
            .orEmpty().filterIsInstance<InputBinding.Axis>()
        assertEquals(2, axes.size)
        assertTrue(axes.all { it.analogRole == dev.cannoli.scorza.input.AnalogRole.ANALOG_STICK })
    }

    @Test
    fun `a trigger still held from the previous question is not captured as the next one`() {
        // Analog triggers do not snap back the instant the last question was answered, so the axis
        // is still deflected when the next one opens. Pressing L2 must not also bind what follows.
        val c = LegendWizardController()
        c.completeRequiredActions()
        repeat(4) {
            c.onKeyCaptured(19 + it)
            Thread.sleep(dev.cannoli.scorza.input.BindingCapture.WIZARD_SETTLE_MS + 20)
            c.tickCapture()
        }
        c.onAppearanceChosen(GlyphStyle.REDMOND)
        var guard = 0
        while (c.state.value.capturing != CanonicalButton.BTN_L2 && guard++ < 24) c.skip()
        assertEquals(CanonicalButton.BTN_L2, c.state.value.capturing)

        c.captureRawAxisEvent(mapOf(17 to 1f))
        Thread.sleep(dev.cannoli.scorza.input.BindingCapture.WIZARD_SETTLE_MS + 20)
        c.tickCapture()
        val afterL2 = c.state.value.capturing
        assertEquals(CanonicalButton.BTN_R, afterL2)

        // Still held, and the question has moved on. It must be ignored until it returns to rest.
        c.captureRawAxisEvent(mapOf(17 to 1f))
        Thread.sleep(dev.cannoli.scorza.input.BindingCapture.WIZARD_SETTLE_MS + 20)
        c.tickCapture()
        assertEquals(CanonicalButton.BTN_R, c.state.value.capturing)

        // Released, skip past R1, then the real R2 press lands on R2 alone.
        c.captureRawAxisEvent(mapOf(17 to 0f))
        c.skip()
        assertEquals(CanonicalButton.BTN_R2, c.state.value.capturing)
        c.captureRawAxisEvent(mapOf(18 to 1f))
        Thread.sleep(dev.cannoli.scorza.input.BindingCapture.WIZARD_SETTLE_MS + 20)
        c.tickCapture()

        val built = c.buildMapping(baseMapping())
        val l2 = built.bindings[CanonicalButton.BTN_L2].orEmpty().filterIsInstance<InputBinding.Axis>()
        val r2 = built.bindings[CanonicalButton.BTN_R2].orEmpty().filterIsInstance<InputBinding.Axis>()
        assertEquals(listOf(17), l2.map { it.axis })
        assertEquals(listOf(18), r2.map { it.axis })
        assertTrue(built.bindings[CanonicalButton.BTN_R].isNullOrEmpty())
    }

    private fun LegendWizardController.answerDpad() = apply {
        repeat(4) {
            onKeyCaptured(19 + it)
            Thread.sleep(dev.cannoli.scorza.input.BindingCapture.WIZARD_SETTLE_MS + 20)
            tickCapture()
        }
    }

    @Test
    fun `choosing Nintendo puts confirm on east, because 96 is the right-hand button there`() {
        val c = LegendWizardController()
        c.completeRequiredActions(confirm = 96, back = 97)
        c.answerDpad()
        c.onAppearanceChosen(GlyphStyle.PLUMBER)
        val built = c.buildMapping(baseMapping())
        assertEquals(CanonicalButton.BTN_EAST, built.menuConfirm)
        assertEquals(CanonicalButton.BTN_SOUTH, built.menuBack)
        assertEquals(96, built.keyCodeOf(CanonicalButton.BTN_EAST))
        assertEquals(97, built.keyCodeOf(CanonicalButton.BTN_SOUTH))
    }

    @Test
    fun `choosing Xbox puts the same press on south, where 96 is the bottom button`() {
        val c = LegendWizardController()
        c.completeRequiredActions(confirm = 96, back = 97)
        c.answerDpad()
        c.onAppearanceChosen(GlyphStyle.REDMOND)
        val built = c.buildMapping(baseMapping())
        assertEquals(CanonicalButton.BTN_SOUTH, built.menuConfirm)
        assertEquals(CanonicalButton.BTN_EAST, built.menuBack)
        assertEquals(96, built.keyCodeOf(CanonicalButton.BTN_SOUTH))
    }

    @Test
    fun `the remaining faces are north and west whichever layout is chosen`() {
        for (style in listOf(GlyphStyle.PLUMBER, GlyphStyle.REDMOND)) {
            val c = LegendWizardController()
            c.completeRequiredActions(confirm = 96, back = 97)
            c.answerDpad()
            c.onAppearanceChosen(style)
            val asked = mutableListOf<CanonicalButton>()
            var guard = 0
            while (c.state.value.step == WizardStep.Capture && guard++ < 4) {
                asked += c.state.value.capturing!!
                c.skip()
            }
            assertTrue("$style asked $asked", asked.take(2).containsAll(
                listOf(CanonicalButton.BTN_NORTH, CanonicalButton.BTN_WEST)
            ))
        }
    }
}
