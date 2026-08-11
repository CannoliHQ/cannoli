package dev.cannoli.scorza.input.legend

import dev.cannoli.igm.CanonicalButton
import dev.cannoli.scorza.input.GlyphStyle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class WizardStep { PressSouth, PressPrimary, Done }

class LegendWizardController {
    private val _step = MutableStateFlow(WizardStep.PressSouth)
    val step: StateFlow<WizardStep> = _step

    private var sonyHint: GlyphStyle? = null
    private var k1: Int? = null
    private var k2: Int? = null
    private var result: LegendProfile? = null

    fun start(sonyGlyphHint: GlyphStyle?) {
        sonyHint = sonyGlyphHint
        k1 = null
        k2 = null
        result = null
        _step.value = WizardStep.PressSouth
    }

    fun onKeyCaptured(keyCode: Int) {
        when (_step.value) {
            WizardStep.PressSouth -> {
                k1 = keyCode
                _step.value = WizardStep.PressPrimary
            }
            WizardStep.PressPrimary -> {
                k2 = keyCode
                result = classify(k1!!, keyCode, sonyHint)
                _step.value = WizardStep.Done
            }
            WizardStep.Done -> {}
        }
    }

    fun profile(): LegendProfile? = result

    // south <- the captured bottom; on nintendo the captured A is east; the remaining standard
    // face codes fill the remaining positions by their standard position.
    fun faceBindings(): Map<CanonicalButton, Int> {
        val p = result ?: return emptyMap()
        val bottom = k1 ?: return emptyMap()
        val b = LinkedHashMap<CanonicalButton, Int>()
        b[CanonicalButton.BTN_SOUTH] = bottom
        if (p.faceLayout == FaceLayout.NINTENDO) k2?.let { b[CanonicalButton.BTN_EAST] = it }
        val used = b.values.toSet()
        for ((code, pos) in STANDARD_POSITION) {
            if (code !in used && pos !in b.keys) b[pos] = code
        }
        return b
    }

    companion object {
        private val STANDARD_POSITION = linkedMapOf(
            96 to CanonicalButton.BTN_SOUTH,
            97 to CanonicalButton.BTN_EAST,
            99 to CanonicalButton.BTN_WEST,
            100 to CanonicalButton.BTN_NORTH,
        )
    }
}
