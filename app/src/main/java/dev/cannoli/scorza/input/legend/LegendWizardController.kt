package dev.cannoli.scorza.input.legend

import dev.cannoli.igm.CanonicalButton
import dev.cannoli.scorza.input.DeviceMapping
import dev.cannoli.scorza.input.GlyphStyle
import dev.cannoli.scorza.input.InputBinding
import dev.cannoli.scorza.input.MappingSource
import dev.cannoli.scorza.input.resolver.RetroArchAutoconfigImporter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class WizardStep { PressSouth, PressPrimary, PressMenu, Done }

class LegendWizardController {
    private val _step = MutableStateFlow(WizardStep.PressSouth)
    val step: StateFlow<WizardStep> = _step

    private var sonyHint: GlyphStyle? = null
    private var k1: Int? = null
    private var k2: Int? = null
    private var menuKeyCode: Int? = null
    private var result: LegendProfile? = null

    fun start(sonyGlyphHint: GlyphStyle?) {
        sonyHint = sonyGlyphHint
        k1 = null
        k2 = null
        menuKeyCode = null
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
                _step.value = WizardStep.PressMenu
            }
            WizardStep.PressMenu -> {
                menuKeyCode = keyCode
                _step.value = WizardStep.Done
            }
            WizardStep.Done -> {}
        }
    }

    fun profile(): LegendProfile? = result

    // Replaces only the four face slots on top of the base mapping so dpad/shoulders/triggers/etc
    // carry over unchanged.
    fun buildMapping(base: DeviceMapping): DeviceMapping {
        val p = result ?: return base
        val faceSlots = faceBindings().mapValues { (_, kc) -> listOf(InputBinding.Button(kc)) }
        val merged = base.bindings.toMutableMap()
        faceSlots.forEach { (canon, binds) -> merged[canon] = binds }
        menuKeyCode?.let { merged[CanonicalButton.BTN_MENU] = listOf(InputBinding.Button(it)) }
        return base.copy(
            bindings = merged,
            menuConfirm = p.menuConfirm,
            menuBack = RetroArchAutoconfigImporter.oppositeOf(p.menuConfirm),
            glyphStyle = p.glyphStyle,
            userEdited = true,
            source = MappingSource.USER_WIZARD,
        )
    }

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
