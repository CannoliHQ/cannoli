package dev.cannoli.scorza.input.legend

import dev.cannoli.igm.CanonicalButton
import dev.cannoli.igm.ShortcutAction
import dev.cannoli.igm.ShortcutTable
import dev.cannoli.scorza.input.DeviceMapping
import dev.cannoli.scorza.input.GlyphStyle
import dev.cannoli.scorza.input.InputBinding
import dev.cannoli.scorza.input.MappingSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

enum class WizardStep {
    PressConfirm, PressBack, BackAgain, PressStart, PressMenu,
    Capture, Appearance, MenuChoice, Done,
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
 * A question the wizard asks once confirm, back and start are known.
 *
 * Select comes first, straight after start, because the menu list offers select + start only when
 * select is a key. [Appearance] and [MenuChoice] are not captures: they are the lists in the flow,
 * which is why neither can be asked before the D-pad has been bound and there is a way to move
 * through them. That is also why the D-pad comes before the menu question.
 */
sealed interface WizardPrompt {
    data class Button(val canonical: CanonicalButton) : WizardPrompt
    data object Appearance : WizardPrompt

    /** The menu button, the one capture start can skip before the lists. */
    data object Menu : WizardPrompt

    /** How the menu opens on a pad that has no menu button, asked straight after [Menu] is skipped. */
    data object MenuChoice : WizardPrompt
}

/** The ways into the menu offered to a pad with no menu button, in the order the list shows them. */
enum class MenuChord { SELECT_START, HOLD_START }

val MENU_CHORD_ORDER: List<MenuChord> = listOf(MenuChord.SELECT_START, MenuChord.HOLD_START)

/**
 * The rows the list offers. A chord is matched on keycodes, so select + start needs select to be a
 * key; a skipped select, or one that reports as an axis or hat, leaves only holding start.
 */
fun menuChordChoices(selectIsKey: Boolean): List<MenuChord> =
    if (selectIsKey) MENU_CHORD_ORDER else listOf(MenuChord.HOLD_START)

// Everything after the glyph question, all of it skippable. Select is not here: it is asked straight
// after start. Sticks before their clicks, the order a
// thumb finds them.
private val PROMPT_TAIL: List<CanonicalButton> = listOf(
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
 * The questions after start, for a pad whose confirm and back already took two face positions.
 *
 * The remaining face buttons are asked after [WizardPrompt.Appearance] so the prompt can name them
 * by what is printed on the pad rather than by where they sit.
 */
/** The appearance rows, in the order the picker lists them. */
val GLYPH_ORDER: List<GlyphStyle> = listOf(GlyphStyle.REDMOND, GlyphStyle.PLUMBER, GlyphStyle.SHAPES)

fun wizardPrompts(
    taken: Set<CanonicalButton>,
    offerMenuChoice: Boolean = false,
): List<WizardPrompt> =
    listOf(WizardPrompt.Button(CanonicalButton.BTN_SELECT)) +
        DPAD.map { WizardPrompt.Button(it) } +
        WizardPrompt.Menu +
        (if (offerMenuChoice) listOf(WizardPrompt.MenuChoice) else emptyList()) +
        WizardPrompt.Appearance +
        FACE_ORDER.filterNot { it in taken }.map { WizardPrompt.Button(it) } +
        PROMPT_TAIL.map { WizardPrompt.Button(it) }

// Why a question is being asked again, so the user reads a rejection rather than a press that
// looks like it went missing. Cleared by the next capture.
enum class WizardNotice {
    PressesDidNotMatch,
    BackMustDifferFromConfirm,

    /** Hold start was set without asking, because it was the only way into the menu left. */
    MenuIsHoldStart,
}

/** Where the in-game menu chord goes for a pad that has no menu button. */
interface WizardShortcutStore {
    /** Null while there is nowhere to write, before first run has chosen a card. */
    fun read(): Map<ShortcutAction, Set<Int>>?
    fun save(shortcuts: Map<ShortcutAction, Set<Int>>)
}

data class LegendWizardState(
    val step: WizardStep = WizardStep.PressConfirm,
    val notice: WizardNotice? = null,
    /** The button being asked for while [step] is [WizardStep.Capture]. */
    val capturing: CanonicalButton? = null,
    /** How far through the questions after start, for the progress pips. */
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
    /** Which menu row is highlighted, while [step] is [WizardStep.MenuChoice]. */
    val menuChoiceIndex: Int = 0,
    /** The rows the menu list offers, which depend on whether select was captured as a key. */
    val menuChoices: List<MenuChord> = emptyList(),
) {
    /** Undo is offered once back is a known button, which is not true for the first two questions. */
    val canUndo: Boolean get() = when (step) {
        WizardStep.PressConfirm, WizardStep.PressBack, WizardStep.BackAgain, WizardStep.Done -> false
        else -> true
    }

    /** Skippable unless the pad cannot be operated without it. */
    val canSkip: Boolean get() = when (step) {
        WizardStep.Appearance, WizardStep.PressMenu -> true
        WizardStep.Capture -> capturing !in REQUIRED_CAPTURES
        else -> false
    }

    /** Which skip line the question shows. The menu question has its own, in place of the generic. */
    val skipLine: WizardSkipLine? get() = when {
        step == WizardStep.PressMenu -> WizardSkipLine.Menu
        step == WizardStep.Capture && canSkip -> WizardSkipLine.Generic
        else -> null
    }
}

enum class WizardSkipLine { Generic, Menu }

class LegendWizardController(
    private val shortcutStore: WizardShortcutStore? = null,
    // App-private, like the autoconfig staging dir, so a chord from a run finished before first run
    // chose a card survives a process death until there is a shortcuts.ini to merge it into.
    private val chordStagingDirProvider: () -> File? = { null },
) {
    private val _state = MutableStateFlow(LegendWizardState())
    val state: StateFlow<LegendWizardState> = _state

    private var confirmKeyCode: Int? = null
    private var confirmRunCount: Int = 0
    private var backKeyCode: Int? = null
    private var menuKeyCode: Int? = null
    private var startKeyCode: Int? = null
    private var menuSkipped: Boolean = false
    private var menuChord: MenuChord? = null
    private var menuChoiceIndex: Int = 0
    // What this run last put in shortcuts.ini, so finishing again after going back replaces it
    // rather than being refused by it.
    private var writtenChord: Pair<ShortcutAction, Set<Int>>? = null
    private var staged = false
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
        menuSkipped = false
        menuChord = null
        menuChoiceIndex = 0
        writtenChord = null
        staged = false
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
            WizardStep.PressStart -> {
                startKeyCode = keyCode
                beginPrompts()
            }
            // A pad with no menu button, or one the system swallows, skips with start and picks
            // another way into the menu from the list that follows.
            WizardStep.PressMenu -> {
                if (consumeControlPress(keyCode)) return
                menuKeyCode = keyCode
                advance()
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
            WizardStep.MenuChoice -> {
                if (consumeControlPress(keyCode)) return
                when (keyCode) {
                    in capturedKeyCodes(CanonicalButton.BTN_UP) -> moveMenuChoice(-1)
                    in capturedKeyCodes(CanonicalButton.BTN_DOWN) -> moveMenuChoice(1)
                    confirmKeyCode -> _state.value.let { s ->
                        s.menuChoices.getOrNull(s.menuChoiceIndex)?.let(::onMenuChordChosen)
                    }
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
        if (_state.value.step == WizardStep.Appearance || _state.value.step == WizardStep.MenuChoice) {
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
        if (dir != 0 && appearanceAxisDir == 0) {
            if (_state.value.step == WizardStep.MenuChoice) moveMenuChoice(dir) else moveAppearance(dir)
        }
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

    private fun moveMenuChoice(delta: Int) {
        val s = _state.value
        val next = (s.menuChoiceIndex + delta).coerceIn(0, (s.menuChoices.size - 1).coerceAtLeast(0))
        if (next != s.menuChoiceIndex) _state.value = s.copy(menuChoiceIndex = next)
    }

    private fun beginPrompts() {
        prompts = buildPrompts() ?: return
        promptIndex = 0
        publishPrompt()
    }

    private fun buildPrompts(): List<WizardPrompt>? {
        val confirm = confirmKeyCode ?: return null
        val back = backKeyCode ?: return null
        // Confirm and back always take two of the four faces, and which two does not depend on the
        // layout, so the remaining pair is the same either way and can be settled before the
        // appearance question is asked.
        val layout = layoutFor(chosenGlyphStyle)
        return wizardPrompts(
            taken = setOf(confirmFace(confirm, layout), backFace(confirm, back, layout)),
            offerMenuChoice = menuSkipped && selectIsKey(),
        )
    }

    private fun selectIsKey(): Boolean = capturedKeyCodes(CanonicalButton.BTN_SELECT).isNotEmpty()

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

    /**
     * Reopen a finished run at its last question, with every answer still in place.
     *
     * Not a restart: the point is that back keeps meaning back. Someone who reaches the end and
     * realises the last few buttons are wrong steps backwards through them one at a time, rather
     * than answering twenty questions again to reach the one they meant.
     */
    fun resumeAtLastPrompt() {
        if (prompts.isEmpty()) return
        promptIndex = prompts.lastIndex
        publishPrompt()
    }

    /** The binding the capture engine settled on for the button being asked about. */
    fun onButtonCaptured(bindings: List<InputBinding>) {
        val canonical = (prompts.getOrNull(promptIndex) as? WizardPrompt.Button)?.canonical ?: return
        if (bindings.isEmpty()) return
        captured[canonical] = bindings
        advance()
    }

    fun onMenuChordChosen(chord: MenuChord) {
        val s = _state.value
        if (s.step != WizardStep.MenuChoice || chord !in s.menuChoices) return
        menuChord = chord
        menuChoiceIndex = s.menuChoices.indexOf(chord)
        advance()
    }

    fun onAppearanceChosen(style: GlyphStyle) {
        if (_state.value.step != WizardStep.Appearance) return
        chosenGlyphStyle = style
        advance()
    }

    /** Leaves the button unbound rather than guessing at it, which is the point of asking. */
    fun skip() {
        val s = _state.value
        if (!s.canSkip) return
        if (s.step == WizardStep.PressMenu) {
            menuKeyCode = null
            menuSkipped = true
            // A list of one is not a question, so holding start is simply set and said.
            if (!selectIsKey()) menuChord = MenuChord.HOLD_START
            prompts = buildPrompts() ?: return
            advance()
            return
        }
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
            WizardStep.PressMenu, WizardStep.Appearance, WizardStep.Capture,
            WizardStep.MenuChoice -> {
                if (promptIndex == 0) {
                    startKeyCode = null
                    prompts = emptyList()
                    publish(WizardStep.PressStart)
                } else {
                    promptIndex -= 1
                    when (val p = prompts[promptIndex]) {
                        is WizardPrompt.Button -> captured.remove(p.canonical)
                        WizardPrompt.Appearance -> chosenGlyphStyle = null
                        WizardPrompt.Menu -> {
                            menuKeyCode = null
                            menuSkipped = false
                            menuChord = null
                            prompts = buildPrompts() ?: prompts
                        }
                        WizardPrompt.MenuChoice -> menuChord = null
                    }
                    publishPrompt()
                }
            }
            else -> {}
        }
    }

    private fun advance() {
        promptIndex += 1
        if (promptIndex >= prompts.size) {
            recordMenuChord()
            publish(WizardStep.Done)
        } else {
            publishPrompt()
        }
    }

    private fun pendingChord(): Pair<ShortcutAction, Set<Int>>? {
        if (!menuSkipped) return null
        val start = startKeyCode ?: return null
        return when (menuChord) {
            MenuChord.SELECT_START -> {
                val select = capturedKeyCodes(CanonicalButton.BTN_SELECT).firstOrNull() ?: return null
                setOf(select, start).takeIf { it.size == 2 }?.let { ShortcutAction.OPEN_MENU to it }
            }
            MenuChord.HOLD_START -> ShortcutAction.OPEN_MENU_HOLD to setOf(start)
            null -> null
        }
    }

    // Written at every finish, not only the first, so a reopened run that changes the pick or
    // answers the menu question after all replaces what the earlier finish left behind.
    private fun recordMenuChord() {
        val chord = pendingChord()
        val existing = shortcutStore?.read()
        if (existing != null) {
            val own = writtenChord?.takeIf { (action, keys) -> existing[action] == keys }
            val base = if (own != null) existing + (own.first to emptySet()) else existing
            writtenChord = null
            if (chord != null && mergeMenuChord(base, chord)) {
                writtenChord = chord
            } else if (own != null) {
                shortcutStore?.save(base)
            }
            return
        }
        val dir = chordStagingDirProvider() ?: return
        val file = File(dir, MENU_CHORD_FILE)
        if (chord == null) {
            if (staged) file.delete()
            staged = false
            return
        }
        staged = runCatching {
            dir.mkdirs()
            dev.cannoli.scorza.input.autoconfig.writeCfgAtomic(
                file, "${chord.first.name}=${ShortcutTable.formatChord(chord.second)}",
            )
        }.isSuccess
    }

    /**
     * Moves a chord staged before first run chose a card into shortcuts.ini. The staged file is
     * dropped either way, a malformed one without writing anything.
     */
    fun flushMenuChord() {
        val dir = chordStagingDirProvider() ?: return
        val file = File(dir, MENU_CHORD_FILE).takeIf { it.isFile } ?: return
        val existing = shortcutStore?.read() ?: return
        runCatching { file.readText() }.getOrNull()?.let(::parseStagedChord)
            ?.let { mergeMenuChord(existing, it) }
        file.delete()
        dir.delete()
    }

    // Either way into the menu already bound means the user has one, and theirs wins.
    private fun mergeMenuChord(
        existing: Map<ShortcutAction, Set<Int>>,
        chord: Pair<ShortcutAction, Set<Int>>,
    ): Boolean {
        if (MENU_ACTIONS.any { !existing[it].isNullOrEmpty() }) return false
        shortcutStore?.save(existing + chord)
        return true
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
            WizardPrompt.Menu -> {
                capture.cancel()
                _state.value = LegendWizardState(
                    step = WizardStep.PressMenu,
                    promptIndex = promptIndex,
                    promptCount = prompts.size,
                )
            }
            WizardPrompt.MenuChoice -> {
                capture.cancel()
                appearanceAxisDir = 0
                // Worked out on every visit, so going back and changing select changes the rows.
                val choices = menuChordChoices(selectIsKey())
                _state.value = LegendWizardState(
                    step = WizardStep.MenuChoice,
                    promptIndex = promptIndex,
                    promptCount = prompts.size,
                    menuChoiceIndex = menuChoiceIndex.coerceIn(0, choices.lastIndex),
                    menuChoices = choices,
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
                    notice = if (menuSkipped && !selectIsKey()) WizardNotice.MenuIsHoldStart else null,
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
        val menuKey = menuKeyCode
        if (menuKey == null && !menuSkipped) return base
        val startKey = startKeyCode ?: return base
        val layout = layoutFor(chosenGlyphStyle)
        val confirmCanonical = confirmFace(confirmKey, layout)
        val backCanonical = backFace(confirmKey, backKey, layout)
        val merged = base.bindings.toMutableMap()
        merged[confirmCanonical] = listOf(InputBinding.Button(confirmKey))
        merged[backCanonical] = listOf(InputBinding.Button(backKey))
        if (menuKey != null) merged[CanonicalButton.BTN_MENU] = listOf(InputBinding.Button(menuKey))
        else merged.remove(CanonicalButton.BTN_MENU)
        merged[CanonicalButton.BTN_START] = listOf(InputBinding.Button(startKey))
        // Everything the user answered after start. A button they skipped is absent from this
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
        const val MENU_CHORD_FILE = "menu_chord"

        private val MENU_ACTIONS = listOf(ShortcutAction.OPEN_MENU, ShortcutAction.OPEN_MENU_HOLD)

        // One line, as shortcuts.ini would hold it. Anything else is malformed and writes nothing.
        internal fun parseStagedChord(text: String): Pair<ShortcutAction, Set<Int>>? {
            val (name, value) = text.trim().split("=", limit = 2).takeIf { it.size == 2 } ?: return null
            val action = MENU_ACTIONS.firstOrNull { it.name == name.trim() } ?: return null
            val parts = value.split(",").map { it.trim().toIntOrNull() ?: return null }
            val keys = parts.toSet()
            val expected = if (action == ShortcutAction.OPEN_MENU) 2 else 1
            if (parts.size != expected || keys.size != expected) return null
            return action to keys
        }

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
