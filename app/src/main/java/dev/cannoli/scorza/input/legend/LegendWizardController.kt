package dev.cannoli.scorza.input.legend

import dev.cannoli.igm.CanonicalButton
import dev.cannoli.scorza.input.DeviceMapping
import dev.cannoli.scorza.input.GlyphStyle
import dev.cannoli.scorza.input.InputBinding
import dev.cannoli.scorza.input.MappingSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class WizardStep {
    PressConfirm, PressBack, BackAgain, PressStart, PressMenu,
    Capture, Appearance, Done,
}

/**
 * The buttons every pad must have, because Cannoli cannot be operated without them. Everything else
 * may be skipped and is then left unbound rather than guessed at.
 */
val REQUIRED_CAPTURES: Set<CanonicalButton> = setOf(
    CanonicalButton.BTN_UP, CanonicalButton.BTN_DOWN,
    CanonicalButton.BTN_LEFT, CanonicalButton.BTN_RIGHT,
)

/**
 * A question the wizard asks after its four actions are known.
 *
 * [Appearance] is not a capture: it is the one list in the flow, which is why it cannot be asked
 * before the D-pad has been bound and there is a way to move through it.
 */
sealed interface WizardPrompt {
    data class Button(val canonical: CanonicalButton) : WizardPrompt
    data object Appearance : WizardPrompt
}

// Everything after the glyph question, all of it skippable. Sticks before their clicks, the order a
// thumb finds them.
private val PROMPT_TAIL: List<CanonicalButton> = listOf(
    CanonicalButton.BTN_SELECT,
    CanonicalButton.BTN_L, CanonicalButton.BTN_L2,
    CanonicalButton.BTN_R, CanonicalButton.BTN_R2,
    CanonicalButton.BTN_LSTICK_X, CanonicalButton.BTN_LSTICK_Y,
    CanonicalButton.BTN_RSTICK_X, CanonicalButton.BTN_RSTICK_Y,
    CanonicalButton.BTN_L3, CanonicalButton.BTN_R3,
)

private val DPAD: List<CanonicalButton> = listOf(
    CanonicalButton.BTN_UP, CanonicalButton.BTN_DOWN,
    CanonicalButton.BTN_LEFT, CanonicalButton.BTN_RIGHT,
)

private val FACE_ORDER: List<CanonicalButton> = listOf(
    CanonicalButton.BTN_NORTH, CanonicalButton.BTN_EAST,
    CanonicalButton.BTN_SOUTH, CanonicalButton.BTN_WEST,
)

/**
 * The questions after phase one, for a pad whose confirm and back already took two face positions.
 *
 * The remaining face buttons are asked after [WizardPrompt.Appearance] so the prompt can name them
 * by what is printed on the pad rather than by where they sit.
 */
/** The appearance rows, in the order the picker lists them. */
val GLYPH_ORDER: List<GlyphStyle> = listOf(GlyphStyle.REDMOND, GlyphStyle.PLUMBER, GlyphStyle.SHAPES)

fun wizardPrompts(taken: Set<CanonicalButton>): List<WizardPrompt> =
    DPAD.map { WizardPrompt.Button(it) } +
        WizardPrompt.Appearance +
        FACE_ORDER.filterNot { it in taken }.map { WizardPrompt.Button(it) } +
        PROMPT_TAIL.map { WizardPrompt.Button(it) }

// Why a question is being asked again, so the user reads a rejection rather than a press that
// looks like it went missing. Cleared by the next capture.
enum class WizardNotice { PressesDidNotMatch, BackMustDifferFromConfirm }

data class LegendWizardState(
    val step: WizardStep = WizardStep.PressConfirm,
    val notice: WizardNotice? = null,
    /** The button being asked for while [step] is [WizardStep.Capture]. */
    val capturing: CanonicalButton? = null,
    /** How far through the questions after phase one, for the progress pips. */
    val promptIndex: Int = 0,
    val promptCount: Int = 0,
    /** Which appearance row is highlighted, while [step] is [WizardStep.Appearance]. */
    val appearanceIndex: Int = 0,
    /** Chosen at the appearance question, and what later prompts name buttons with. */
    val glyphStyle: GlyphStyle? = null,
    /** Presses into the confirm run, which is the only question that draws progress. */
    val confirmRunCount: Int = 0,
    /** Where back sits once the layout is settled, so the legend can print the button's own glyph. */
    val backFace: CanonicalButton? = null,
) {
    /** Undo is offered once back is a known button, which is not true for the first two questions. */
    val canUndo: Boolean get() = when (step) {
        WizardStep.PressConfirm, WizardStep.PressBack, WizardStep.BackAgain, WizardStep.Done -> false
        else -> true
    }

    /** Skippable unless the pad cannot be operated without it. */
    val canSkip: Boolean get() = when (step) {
        WizardStep.Appearance -> true
        WizardStep.Capture -> capturing !in REQUIRED_CAPTURES
        else -> false
    }
}

class LegendWizardController {
    private val _state = MutableStateFlow(LegendWizardState())
    val state: StateFlow<LegendWizardState> = _state

    private var confirmKeyCode: Int? = null
    private var confirmRunCount: Int = 0
    private var backKeyCode: Int? = null
    private var menuKeyCode: Int? = null
    private var startKeyCode: Int? = null
    private var prompts: List<WizardPrompt> = emptyList()
    private var promptIndex: Int = 0
    private val captured = linkedMapOf<CanonicalButton, List<InputBinding>>()
    private var chosenGlyphStyle: GlyphStyle? = null

    // No timeout: the wizard has nothing to fall back to and the user may be turning the pad over
    // looking for the button being asked about.
    private val capture = dev.cannoli.scorza.input.BindingCapture(
        timeoutMs = null,
        settleMs = dev.cannoli.scorza.input.BindingCapture.WIZARD_SETTLE_MS,
    )

    /**
     * [confirmedKeyCode] is the button the welcome run already established, which is why the wizard
     * does not ask for confirm again on the path that sends most pads here. Null only for a pad that
     * reached the wizard some other way, and then confirm is the first question.
     */
    fun start(confirmedKeyCode: Int? = null) {
        capture.cancel()
        confirmKeyCode = confirmedKeyCode
        backKeyCode = null
        menuKeyCode = null
        startKeyCode = null
        confirmRunCount = 0
        prompts = emptyList()
        promptIndex = 0
        captured.clear()
        chosenGlyphStyle = null
        publish(if (confirmedKeyCode == null) WizardStep.PressConfirm else WizardStep.PressBack)
    }

    fun onKeyCaptured(keyCode: Int) {
        when (_state.value.step) {
            // The same run of presses the welcome step asks for, for a pad that arrived here
            // without one. A press of anything else empties the run rather than failing it.
            WizardStep.PressConfirm -> {
                if (keyCode == confirmKeyCode) {
                    confirmRunCount += 1
                } else {
                    confirmKeyCode = keyCode
                    confirmRunCount = 1
                }
                if (confirmRunCount >= CONFIRM_PRESSES_REQUIRED) publish(WizardStep.PressBack)
                else _state.value = LegendWizardState(
                    step = WizardStep.PressConfirm,
                    confirmRunCount = confirmRunCount,
                )
            }
            // One button doing both leaves the pad able to confirm but never to go back, which
            // strands the user in the flow meant to fix their controller.
            WizardStep.PressBack -> {
                if (keyCode == confirmKeyCode) {
                    publish(WizardStep.PressBack, WizardNotice.BackMustDifferFromConfirm)
                } else {
                    backKeyCode = keyCode
                    publish(WizardStep.BackAgain)
                }
            }
            WizardStep.BackAgain -> {
                if (keyCode == confirmKeyCode) {
                    backKeyCode = null
                    publish(WizardStep.PressBack, WizardNotice.BackMustDifferFromConfirm)
                } else if (keyCode == backKeyCode) {
                    publish(WizardStep.PressStart)
                } else {
                    backKeyCode = null
                    publish(WizardStep.PressBack, WizardNotice.PressesDidNotMatch)
                }
            }
            // Menu and start are mandatory like the rest: a pad with neither cannot work the games
            // Cannoli launches, so there is no trapped-pad case to offer a way out of.
            WizardStep.PressStart -> {
                startKeyCode = keyCode
                publish(WizardStep.PressMenu)
            }
            WizardStep.PressMenu -> {
                menuKeyCode = keyCode
                beginPrompts()
            }
            // Raw presses here are the capture engine's, except the two the wizard reserved: start
            // skips a button the pad does not have, back undoes the last answer. Both are bound by
            // now, which is why neither can be captured for anything else.
            WizardStep.Appearance -> {
                if (consumeControlPress(keyCode)) return
                // The D-pad just captured is not in any applied mapping yet, so the only way to
                // work this list is the raw codes the user gave for it a moment ago.
                when (keyCode) {
                    in capturedKeyCodes(CanonicalButton.BTN_UP) -> moveAppearance(-1)
                    in capturedKeyCodes(CanonicalButton.BTN_DOWN) -> moveAppearance(1)
                    confirmKeyCode -> onAppearanceChosen(GLYPH_ORDER[_state.value.appearanceIndex])
                }
            }
            // Skip and undo are claimed first, so neither is ever taken as the answer. Anything
            // else is the button the question asked for.
            WizardStep.Capture -> if (!consumeControlPress(keyCode)) capture.onKey(keyCode)
            WizardStep.Done -> {}
        }
    }


    /** Axis and hat presses, which never arrive as keycodes. */
    fun captureRawAxisEvent(axisValues: Map<Int, Float>) {
        // A D-pad reported as a hat is bound as a Hat, not a keycode, so the appearance list has to
        // be worked from the motion stream as well. Without this a pad whose D-pad is a hat, which
        // is most of them, cannot move the selection at all.
        if (_state.value.step == WizardStep.Appearance) {
            navigateAppearanceByAxis(axisValues)
            return
        }
        // Watched continuously, so a trigger still held from the previous question is known to be
        // held rather than mistaken for the answer to this one.
        capture.observe(axisValues)
    }

    private var appearanceAxisDir = 0

    private fun navigateAppearanceByAxis(axisValues: Map<Int, Float>) {
        val dir = when {
            axisMatchesCaptured(CanonicalButton.BTN_UP, axisValues) -> -1
            axisMatchesCaptured(CanonicalButton.BTN_DOWN, axisValues) -> 1
            else -> 0
        }
        // Edge triggered: a held hat repeats its motion event, and acting on every one would run the
        // selection to the end of the list on a single push.
        if (dir != 0 && appearanceAxisDir == 0) moveAppearance(dir)
        appearanceAxisDir = dir
    }

    private fun axisMatchesCaptured(canonical: CanonicalButton, axisValues: Map<Int, Float>): Boolean =
        captured[canonical].orEmpty().any { binding ->
            when (binding) {
                is InputBinding.Hat -> {
                    val v = axisValues[binding.axis] ?: 0f
                    when (binding.direction) {
                        dev.cannoli.scorza.input.HatDirection.UP,
                        dev.cannoli.scorza.input.HatDirection.LEFT -> v <= -AXIS_ON
                        dev.cannoli.scorza.input.HatDirection.DOWN,
                        dev.cannoli.scorza.input.HatDirection.RIGHT -> v >= AXIS_ON
                    }
                }
                is InputBinding.Axis -> {
                    val v = axisValues[binding.axis] ?: 0f
                    if (binding.activeMax >= 0) v >= AXIS_ON else v <= -AXIS_ON
                }
                is InputBinding.Button -> false
            }
        }

    val isCapturing: Boolean get() = capture.isListening

    val isChoosingAppearance: Boolean get() = _state.value.step == WizardStep.Appearance

    /** Driven on a timer by the host, the way the button editor drives its own capture. */
    fun tickCapture() {
        val outcome = capture.tick()
        if (outcome is dev.cannoli.scorza.input.BindingCapture.Outcome.Captured) {
            onButtonCaptured(outcome.bindings)
        }
    }

    // Only once the user has said which layout their pad uses: before that, which face back sits on
    // is exactly the thing not yet known.
    private fun resolvedBackFace(): CanonicalButton? {
        val style = chosenGlyphStyle ?: return null
        val confirm = confirmKeyCode ?: return null
        val back = backKeyCode ?: return null
        return backFace(confirm, back, layoutFor(style))
    }

    private fun capturedKeyCodes(canonical: CanonicalButton): List<Int> =
        captured[canonical].orEmpty().filterIsInstance<InputBinding.Button>().map { it.keyCode }

    private fun moveAppearance(delta: Int) {
        val s = _state.value
        val next = (s.appearanceIndex + delta).coerceIn(0, GLYPH_ORDER.lastIndex)
        if (next != s.appearanceIndex) _state.value = s.copy(appearanceIndex = next)
    }

    private fun beginPrompts() {
        val confirm = confirmKeyCode ?: return
        val back = backKeyCode ?: return
        // Confirm and back always take two of the four faces, and which two does not depend on the
        // layout, so the remaining pair is the same either way and can be settled before the
        // appearance question is asked.
        val layout = layoutFor(chosenGlyphStyle)
        prompts = wizardPrompts(
            taken = setOf(confirmFace(confirm, layout), backFace(confirm, back, layout))
        )
        promptIndex = 0
        publishPrompt()
    }

    /**
     * True when the press was one of the wizard's own controls rather than a button being bound.
     *
     * Start skips, back undoes. The host asks this before handing a press to the capture engine, so
     * a skip is never mistaken for the answer to the question on screen.
     */
    fun consumeControlPress(keyCode: Int): Boolean {
        val s = _state.value
        if (s.canSkip && keyCode == startKeyCode) { skip(); return true }
        if (s.canUndo && keyCode == backKeyCode) { undo(); return true }
        return false
    }

    /** The binding the capture engine settled on for the button being asked about. */
    fun onButtonCaptured(bindings: List<InputBinding>) {
        val canonical = (prompts.getOrNull(promptIndex) as? WizardPrompt.Button)?.canonical ?: return
        if (bindings.isEmpty()) return
        captured[canonical] = bindings
        advance()
    }

    fun onAppearanceChosen(style: GlyphStyle) {
        if (_state.value.step != WizardStep.Appearance) return
        chosenGlyphStyle = style
        advance()
    }

    /** Leaves the button unbound rather than guessing at it, which is the point of asking. */
    fun skip() {
        if (!_state.value.canSkip) return
        (prompts.getOrNull(promptIndex) as? WizardPrompt.Button)?.let { captured.remove(it.canonical) }
        advance()
    }

    /**
     * Steps back to the previous question and clears the answer it had, so a button bound by
     * mistake is asked again rather than left wrong.
     */
    fun undo() {
        val s = _state.value
        if (!s.canUndo) return
        when (s.step) {
            WizardStep.PressStart -> {
                backKeyCode = null
                publish(WizardStep.PressBack)
            }
            WizardStep.PressMenu -> {
                startKeyCode = null
                publish(WizardStep.PressStart)
            }
            WizardStep.Appearance, WizardStep.Capture -> {
                if (promptIndex == 0) {
                    menuKeyCode = null
                    prompts = emptyList()
                    publish(WizardStep.PressMenu)
                } else {
                    promptIndex -= 1
                    when (val p = prompts[promptIndex]) {
                        is WizardPrompt.Button -> captured.remove(p.canonical)
                        WizardPrompt.Appearance -> chosenGlyphStyle = null
                    }
                    publishPrompt()
                }
            }
            else -> {}
        }
    }

    private fun advance() {
        promptIndex += 1
        if (promptIndex >= prompts.size) publish(WizardStep.Done) else publishPrompt()
    }

    private fun publishPrompt() {
        when (val p = prompts[promptIndex]) {
            is WizardPrompt.Button -> {
                capture.start(p.canonical)
                _state.value = LegendWizardState(
                step = WizardStep.Capture,
                capturing = p.canonical,
                promptIndex = promptIndex,
                    promptCount = prompts.size,
                    glyphStyle = chosenGlyphStyle,
                    backFace = resolvedBackFace(),
                )
            }
            WizardPrompt.Appearance -> {
                capture.cancel()
                appearanceAxisDir = 0
                _state.value = LegendWizardState(
                step = WizardStep.Appearance,
                promptIndex = promptIndex,
                promptCount = prompts.size,
                    appearanceIndex = GLYPH_ORDER.indexOf(chosenGlyphStyle).coerceAtLeast(0),
                )
            }
        }
    }

    fun confirmKeyCode(): Int? = confirmKeyCode

    // Built only from a complete set: the four required actions plus whatever the user answered
    // after them. A question they skipped leaves its button absent here and so unbound.
    fun buildMapping(base: DeviceMapping): DeviceMapping {
        val confirmKey = confirmKeyCode ?: return base
        val backKey = backKeyCode ?: return base
        val menuKey = menuKeyCode ?: return base
        val startKey = startKeyCode ?: return base
        val layout = layoutFor(chosenGlyphStyle)
        val confirmCanonical = confirmFace(confirmKey, layout)
        val backCanonical = backFace(confirmKey, backKey, layout)
        val merged = base.bindings.toMutableMap()
        merged[confirmCanonical] = listOf(InputBinding.Button(confirmKey))
        merged[backCanonical] = listOf(InputBinding.Button(backKey))
        merged[CanonicalButton.BTN_MENU] = listOf(InputBinding.Button(menuKey))
        merged[CanonicalButton.BTN_START] = listOf(InputBinding.Button(startKey))
        // Everything the user answered after phase one. A button they skipped is absent from this
        // map and so keeps whatever the base had, which for a full run is nothing: skipping leaves
        // it unbound rather than guessing at it.
        merged.putAll(captured)
        return base.copy(
            bindings = merged,
            menuConfirm = confirmCanonical,
            menuBack = backCanonical,
            glyphStyle = chosenGlyphStyle ?: base.glyphStyle,
            userEdited = true,
            source = MappingSource.USER_WIZARD,
        )
    }

    private fun publish(step: WizardStep, notice: WizardNotice? = null) {
        _state.value = LegendWizardState(step = step, notice = notice)
    }

    companion object {
        private const val AXIS_ON = 0.6f

        /**
         * Where a face keycode physically sits, which the pad's layout decides.
         *
         * Android hands out the same four codes whatever the shell prints, but they do not land in
         * the same places: on a Nintendo-style pad 96 is the right-hand button, where on the others
         * it is the bottom one. So this cannot be answered until the user has said which layout
         * their controller uses, and answering it early was what bound confirm to the wrong face.
         */
        private fun facePositions(layout: FaceLayout): Map<Int, CanonicalButton> =
            layout.standardFaceBindings().entries.associate { (canonical, keyCode) -> keyCode to canonical }

        private fun layoutFor(style: GlyphStyle?): FaceLayout =
            if (style == GlyphStyle.PLUMBER) FaceLayout.NINTENDO else FaceLayout.STANDARD

        // A button reporting something outside the four face codes has no position of its own, so
        // confirm takes the layout's own confirm slot and back takes the other of the pair.
        private fun confirmFace(confirmKeyCode: Int, layout: FaceLayout): CanonicalButton =
            facePositions(layout)[confirmKeyCode] ?: layout.confirmButton

        private fun backFace(confirmKeyCode: Int, backKeyCode: Int, layout: FaceLayout): CanonicalButton =
            facePositions(layout)[backKeyCode]
                ?: if (confirmFace(confirmKeyCode, layout) == CanonicalButton.BTN_SOUTH) {
                    CanonicalButton.BTN_EAST
                } else {
                    CanonicalButton.BTN_SOUTH
                }
    }
}
