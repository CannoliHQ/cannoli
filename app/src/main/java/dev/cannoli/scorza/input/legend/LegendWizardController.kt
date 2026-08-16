package dev.cannoli.scorza.input.legend

import dev.cannoli.igm.CanonicalButton
import dev.cannoli.scorza.input.DeviceMapping
import dev.cannoli.scorza.input.GlyphStyle
import dev.cannoli.scorza.input.InputBinding
import dev.cannoli.scorza.input.MappingSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class WizardStep { PressConfirm, ConfirmAgain, PressBack, BackAgain, PressMenu, PressStart, Done }

/** Confirm, back, menu and start. The second press of confirm and of back is not a capture of its own. */
const val WIZARD_CAPTURES = 4

// Why a question is being asked again, so the user reads a rejection rather than a press that
// looks like it went missing. Cleared by the next capture.
enum class WizardNotice { PressesDidNotMatch, BackMustDifferFromConfirm }

data class LegendWizardState(
    val step: WizardStep = WizardStep.PressConfirm,
    val notice: WizardNotice? = null,
) {
    // A capture counts once the question it answers is settled, so the confirming second press of
    // confirm and of back does not advance it on its own.
    val capturesDone: Int get() = when (step) {
        WizardStep.PressConfirm, WizardStep.ConfirmAgain -> 0
        WizardStep.PressBack, WizardStep.BackAgain -> 1
        WizardStep.PressMenu -> 2
        WizardStep.PressStart -> 3
        WizardStep.Done -> WIZARD_CAPTURES
    }
}

class LegendWizardController {
    private val _state = MutableStateFlow(LegendWizardState())
    val state: StateFlow<LegendWizardState> = _state

    private var sonyHint: GlyphStyle? = null
    private var confirmKeyCode: Int? = null
    private var backKeyCode: Int? = null
    private var menuKeyCode: Int? = null
    private var startKeyCode: Int? = null
    private var result: LegendProfile? = null

    fun start(sonyGlyphHint: GlyphStyle?) {
        sonyHint = sonyGlyphHint
        confirmKeyCode = null
        backKeyCode = null
        menuKeyCode = null
        startKeyCode = null
        result = null
        publish(WizardStep.PressConfirm)
    }

    fun onKeyCaptured(keyCode: Int) {
        when (_state.value.step) {
            WizardStep.PressConfirm -> {
                confirmKeyCode = keyCode
                publish(WizardStep.ConfirmAgain)
            }
            WizardStep.ConfirmAgain -> {
                if (keyCode == confirmKeyCode) {
                    result = classify(keyCode, sonyHint)
                    publish(WizardStep.PressBack)
                } else {
                    confirmKeyCode = null
                    publish(WizardStep.PressConfirm, WizardNotice.PressesDidNotMatch)
                }
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
                    publish(WizardStep.PressMenu)
                } else {
                    backKeyCode = null
                    publish(WizardStep.PressBack, WizardNotice.PressesDidNotMatch)
                }
            }
            // Menu and start are mandatory like the rest: a pad with neither cannot work the games
            // Cannoli launches, so there is no trapped-pad case to offer a way out of.
            WizardStep.PressMenu -> {
                menuKeyCode = keyCode
                publish(WizardStep.PressStart)
            }
            WizardStep.PressStart -> {
                startKeyCode = keyCode
                publish(WizardStep.Done)
            }
            WizardStep.Done -> {}
        }
    }

    fun profile(): LegendProfile? = result

    fun confirmKeyCode(): Int? = confirmKeyCode

    // Replaces the captured slots on top of the base mapping so dpad/shoulders/triggers/etc carry
    // over unchanged. All four captures are mandatory, so a mapping is only ever built from a
    // complete set.
    fun buildMapping(base: DeviceMapping): DeviceMapping {
        val p = result ?: return base
        val confirmKey = confirmKeyCode ?: return base
        val backKey = backKeyCode ?: return base
        val menuKey = menuKeyCode ?: return base
        val startKey = startKeyCode ?: return base
        val confirmCanonical = confirmFace(confirmKey)
        val backCanonical = backFace(confirmKey, backKey)
        val merged = base.bindings.toMutableMap()
        merged[confirmCanonical] = listOf(InputBinding.Button(confirmKey))
        merged[backCanonical] = listOf(InputBinding.Button(backKey))
        merged[CanonicalButton.BTN_MENU] = listOf(InputBinding.Button(menuKey))
        merged[CanonicalButton.BTN_START] = listOf(InputBinding.Button(startKey))
        return base.copy(
            bindings = merged,
            menuConfirm = confirmCanonical,
            menuBack = backCanonical,
            glyphStyle = p.glyphStyle,
            userEdited = true,
            source = MappingSource.USER_WIZARD,
        )
    }

    private fun publish(step: WizardStep, notice: WizardNotice? = null) {
        _state.value = LegendWizardState(step = step, notice = notice)
    }

    companion object {
        // STANDARD is the canonical face-code table: each keycode sits at the position it is named
        // after, whatever the shell in front of it prints.
        private val FACE_POSITIONS = FaceLayout.STANDARD.standardFaceBindings()
            .entries.associate { (canonical, keyCode) -> keyCode to canonical }

        // A button reporting something outside the four face codes has no position of its own, so
        // confirm takes the bottom slot and back takes the one confirm left: the pad navigates
        // correctly and only the printed glyph is a guess.
        private fun confirmFace(confirmKeyCode: Int): CanonicalButton =
            FACE_POSITIONS[confirmKeyCode] ?: CanonicalButton.BTN_SOUTH

        private fun backFace(confirmKeyCode: Int, backKeyCode: Int): CanonicalButton =
            FACE_POSITIONS[backKeyCode] ?: if (confirmFace(confirmKeyCode) == CanonicalButton.BTN_SOUTH) {
                CanonicalButton.BTN_EAST
            } else {
                CanonicalButton.BTN_SOUTH
            }
    }
}
