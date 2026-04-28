package dev.cannoli.scorza.input.v2.runtime

import dev.cannoli.scorza.input.v2.AnalogRole
import dev.cannoli.scorza.input.v2.CanonicalButton
import dev.cannoli.scorza.input.v2.DeviceTemplate
import dev.cannoli.scorza.input.v2.HatDirection
import dev.cannoli.scorza.input.v2.InputBinding

class PortEvaluator(
    private val template: DeviceTemplate,
    private val analogNoiseThreshold: Float = 0.05f,
) {

    private sealed interface BindingKey {
        data class Key(val keyCode: Int) : BindingKey
        data class Axis(val axis: Int, val direction: Int) : BindingKey
        data class Hat(val axis: Int, val direction: HatDirection) : BindingKey
    }

    private val pressed = mutableSetOf<CanonicalButton>()
    private val analog = mutableMapOf<AnalogRole, Float>()
    private val asserters = mutableMapOf<CanonicalButton, MutableSet<BindingKey>>()

    fun evaluateKeyDown(keyCode: Int, isAndroidRepeat: Boolean): List<CanonicalEvent> {
        if (isAndroidRepeat) return emptyList()
        val deltas = mutableListOf<CanonicalEvent>()
        for ((canonical, bindings) in template.bindings) {
            for (binding in bindings) {
                if (binding is InputBinding.Button && binding.keyCode == keyCode) {
                    val key = BindingKey.Key(binding.keyCode)
                    if (assertSource(canonical, key)) {
                        deltas += CanonicalEvent.Pressed(canonical)
                    }
                }
            }
        }
        return deltas
    }

    fun evaluateKeyUp(keyCode: Int): List<CanonicalEvent> {
        val deltas = mutableListOf<CanonicalEvent>()
        for ((canonical, bindings) in template.bindings) {
            for (binding in bindings) {
                if (binding is InputBinding.Button && binding.keyCode == keyCode) {
                    val key = BindingKey.Key(binding.keyCode)
                    if (releaseSource(canonical, key)) {
                        deltas += CanonicalEvent.Released(canonical)
                    }
                }
            }
        }
        return deltas
    }

    fun currentlyPressed(): Set<CanonicalButton> = pressed.toSet()

    private fun assertSource(canonical: CanonicalButton, source: BindingKey): Boolean {
        val set = asserters.getOrPut(canonical) { mutableSetOf() }
        val firstAssertion = set.isEmpty()
        set.add(source)
        if (firstAssertion) {
            pressed.add(canonical)
            return true
        }
        return false
    }

    private fun releaseSource(canonical: CanonicalButton, source: BindingKey): Boolean {
        val set = asserters[canonical] ?: return false
        if (!set.remove(source)) return false
        if (set.isEmpty()) {
            asserters.remove(canonical)
            pressed.remove(canonical)
            return true
        }
        return false
    }
}
