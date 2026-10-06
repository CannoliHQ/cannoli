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

val REQUIRED_CAPTURES: Set<CanonicalButton> = setOf(
    CanonicalButton.BTN_UP, CanonicalButton.BTN_DOWN,
    CanonicalButton.BTN_LEFT, CanonicalButton.BTN_RIGHT,
)

sealed interface WizardPrompt {
    data class Button(val canonical: CanonicalButton) : WizardPrompt
    data object Appearance : WizardPrompt

    data object Menu : WizardPrompt

    data object MenuChoice : WizardPrompt
}

enum class MenuChord { SELECT_START, HOLD_START }

val MENU_CHORD_ORDER: List<MenuChord> = listOf(MenuChord.SELECT_START, MenuChord.HOLD_START)

fun menuChordChoices(selectIsKey: Boolean): List<MenuChord> =
    if (selectIsKey) MENU_CHORD_ORDER else listOf(MenuChord.HOLD_START)

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

enum class WizardNotice {
    PressesDidNotMatch,
    BackMustDifferFromConfirm,

    MenuIsHoldStart,
}

interface WizardShortcutStore {
    fun read(): Map<ShortcutAction, Set<Int>>?
    fun save(shortcuts: Map<ShortcutAction, Set<Int>>)
}

data class LegendWizardState(
    val step: WizardStep = WizardStep.PressConfirm,
    val notice: WizardNotice? = null,
    val capturing: CanonicalButton? = null,
    val promptIndex: Int = 0,
    val promptCount: Int = 0,
    val appearanceIndex: Int = 0,
    val glyphStyle: GlyphStyle? = null,
    val confirmRunCount: Int = 0,
    val backFace: CanonicalButton? = null,
    val menuChoiceIndex: Int = 0,
    val menuChoices: List<MenuChord> = emptyList(),
) {
    val canUndo: Boolean get() = when (step) {
        WizardStep.PressConfirm, WizardStep.PressBack, WizardStep.BackAgain, WizardStep.Done -> false
        else -> true
    }

    val canSkip: Boolean get() = when (step) {
        WizardStep.Appearance, WizardStep.PressMenu -> true
        WizardStep.Capture -> capturing !in REQUIRED_CAPTURES
        else -> false
    }

    val skipLine: WizardSkipLine? get() = when {
        step == WizardStep.PressMenu -> WizardSkipLine.Menu
        step == WizardStep.Capture && canSkip -> WizardSkipLine.Generic
        else -> null
    }
}

enum class WizardSkipLine { Generic, Menu }

class LegendWizardController(
    private val shortcutStore: WizardShortcutStore? = null,
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
    private var writtenChord: Pair<ShortcutAction, Set<Int>>? = null
    private var staged = false
    private var prompts: List<WizardPrompt> = emptyList()
    private var promptIndex: Int = 0
    private val captured = linkedMapOf<CanonicalButton, List<InputBinding>>()
    private var chosenGlyphStyle: GlyphStyle? = null

    private val capture = dev.cannoli.scorza.input.BindingCapture(
        timeoutMs = null,
        settleMs = dev.cannoli.scorza.input.BindingCapture.WIZARD_SETTLE_MS,
    )

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
            WizardStep.PressMenu -> {
                if (consumeControlPress(keyCode)) return
                menuKeyCode = keyCode
                advance()
            }
            WizardStep.Appearance -> {
                if (consumeControlPress(keyCode)) return
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
            WizardStep.Capture -> if (!consumeControlPress(keyCode)) capture.onKey(keyCode)
            WizardStep.Done -> {}
        }
    }


    fun captureRawAxisEvent(axisValues: Map<Int, Float>) {
        if (_state.value.step == WizardStep.Appearance || _state.value.step == WizardStep.MenuChoice) {
            navigateAppearanceByAxis(axisValues)
            return
        }
        capture.observe(axisValues)
    }

    private var appearanceAxisDir = 0

    private fun navigateAppearanceByAxis(axisValues: Map<Int, Float>) {
        val dir = when {
            axisMatchesCaptured(CanonicalButton.BTN_UP, axisValues) -> -1
            axisMatchesCaptured(CanonicalButton.BTN_DOWN, axisValues) -> 1
            else -> 0
        }
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

    fun tickCapture() {
        val outcome = capture.tick()
        if (outcome is dev.cannoli.scorza.input.BindingCapture.Outcome.Captured) {
            onButtonCaptured(outcome.bindings)
        }
    }

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
        val layout = layoutFor(chosenGlyphStyle)
        return wizardPrompts(
            taken = setOf(confirmFace(confirm, layout), backFace(confirm, back, layout)),
            offerMenuChoice = menuSkipped && selectIsKey(),
        )
    }

    private fun selectIsKey(): Boolean = capturedKeyCodes(CanonicalButton.BTN_SELECT).isNotEmpty()

    fun consumeControlPress(keyCode: Int): Boolean {
        val s = _state.value
        if (s.canSkip && keyCode == startKeyCode) { skip(); return true }
        if (s.canUndo && keyCode == backKeyCode) { undo(); return true }
        return false
    }

    fun resumeAtLastPrompt() {
        if (prompts.isEmpty()) return
        promptIndex = prompts.lastIndex
        publishPrompt()
    }

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

    fun skip() {
        val s = _state.value
        if (!s.canSkip) return
        if (s.step == WizardStep.PressMenu) {
            menuKeyCode = null
            menuSkipped = true
            if (!selectIsKey()) menuChord = MenuChord.HOLD_START
            prompts = buildPrompts() ?: return
            advance()
            return
        }
        (prompts.getOrNull(promptIndex) as? WizardPrompt.Button)?.let { captured.remove(it.canonical) }
        advance()
    }

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

    fun flushMenuChord() {
        val dir = chordStagingDirProvider() ?: return
        val file = File(dir, MENU_CHORD_FILE).takeIf { it.isFile } ?: return
        val existing = shortcutStore?.read() ?: return
        runCatching { file.readText() }.getOrNull()?.let(::parseStagedChord)
            ?.let { mergeMenuChord(existing, it) }
        file.delete()
        dir.delete()
    }

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

        internal fun parseStagedChord(text: String): Pair<ShortcutAction, Set<Int>>? {
            val (name, value) = text.trim().split("=", limit = 2).takeIf { it.size == 2 } ?: return null
            val action = MENU_ACTIONS.firstOrNull { it.name == name.trim() } ?: return null
            val parts = value.split(",").map { it.trim().toIntOrNull() ?: return null }
            val keys = parts.toSet()
            val expected = if (action == ShortcutAction.OPEN_MENU) 2 else 1
            if (parts.size != expected || keys.size != expected) return null
            return action to keys
        }

        private fun facePositions(layout: FaceLayout): Map<Int, CanonicalButton> =
            layout.standardFaceBindings().entries.associate { (canonical, keyCode) -> keyCode to canonical }

        private fun layoutFor(style: GlyphStyle?): FaceLayout =
            if (style == GlyphStyle.PLUMBER) FaceLayout.NINTENDO else FaceLayout.STANDARD

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
