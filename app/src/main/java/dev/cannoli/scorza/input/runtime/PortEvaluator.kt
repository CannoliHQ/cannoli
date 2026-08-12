package dev.cannoli.scorza.input.runtime

import dev.cannoli.scorza.input.AnalogRole
import dev.cannoli.scorza.input.CanonicalButton
import dev.cannoli.scorza.input.DeviceMapping
import dev.cannoli.scorza.input.HatDirection
import dev.cannoli.scorza.input.InputBinding
import kotlin.math.abs
import kotlin.math.sign

class PortEvaluator(
    private val mapping: DeviceMapping,
    private val analogNoiseThreshold: Float = 0.05f,
) {

    private sealed interface BindingKey {
        data class Key(val keyCode: Int) : BindingKey
        data class Axis(val axis: Int, val direction: Int) : BindingKey
        data class Hat(val axis: Int, val direction: HatDirection) : BindingKey
    }

    private val pressed = mutableSetOf<CanonicalButton>()
    private val analog = mutableMapOf<CanonicalButton, Float>()
    private val asserters = mutableMapOf<CanonicalButton, MutableSet<BindingKey>>()

    fun evaluateKeyDown(keyCode: Int, isAndroidRepeat: Boolean): List<CanonicalEvent> {
        if (isAndroidRepeat) return emptyList()
        val deltas = mutableListOf<CanonicalEvent>()
        forEachBinding { canonical, binding ->
            if (binding is InputBinding.Button && binding.keyCode == keyCode) {
                if (assertSource(canonical, BindingKey.Key(binding.keyCode))) {
                    deltas += CanonicalEvent.Pressed(canonical)
                }
            }
        }
        return deltas
    }

    fun evaluateKeyUp(keyCode: Int): List<CanonicalEvent> {
        val deltas = mutableListOf<CanonicalEvent>()
        forEachBinding { canonical, binding ->
            if (binding is InputBinding.Button && binding.keyCode == keyCode) {
                if (releaseSource(canonical, BindingKey.Key(binding.keyCode))) {
                    deltas += CanonicalEvent.Released(canonical)
                }
            }
        }
        return deltas
    }

    fun evaluateAxis(axisValues: Map<Int, Float>): List<CanonicalEvent> {
        val deltas = mutableListOf<CanonicalEvent>()
        forEachBinding { canonical, binding ->
            when (binding) {
                is InputBinding.Axis -> {
                    val raw = axisValues[binding.axis] ?: return@forEachBinding
                    val direction = directionOf(binding)
                    if (binding.analogRole == AnalogRole.DIGITAL_BUTTON) {
                        val key = BindingKey.Axis(binding.axis, direction)
                        if (binding.isDigitalPressed(raw)) {
                            if (assertSource(canonical, key)) {
                                deltas += CanonicalEvent.Pressed(canonical)
                            }
                        } else {
                            if (releaseSource(canonical, key)) {
                                deltas += CanonicalEvent.Released(canonical)
                            }
                        }
                    } else {
                        val normalized = binding.normalize(raw)
                        val previous = analog[canonical] ?: 0f
                        if (abs(normalized - previous) >= analogNoiseThreshold) {
                            analog[canonical] = normalized
                            deltas += CanonicalEvent.AnalogChanged(canonical, normalized)
                        }
                    }
                }
                is InputBinding.Hat -> {
                    val raw = axisValues[binding.axis] ?: return@forEachBinding
                    val key = BindingKey.Hat(binding.axis, binding.direction)
                    if (binding.isPressed(raw)) {
                        if (assertSource(canonical, key)) {
                            deltas += CanonicalEvent.Pressed(canonical)
                        }
                    } else {
                        if (releaseSource(canonical, key)) {
                            deltas += CanonicalEvent.Released(canonical)
                        }
                    }
                }
                is InputBinding.Button -> Unit
            }
        }
        return deltas
    }

    fun currentlyPressed(): Set<CanonicalButton> = pressed.toSet()

    fun analogValue(canonical: CanonicalButton): Float = analog[canonical] ?: 0f

    fun keyCodeIsBound(keyCode: Int): Boolean {
        for ((_, bindings) in mapping.bindings) {
            for (binding in bindings) {
                if (binding is InputBinding.Button && binding.keyCode == keyCode) return true
            }
        }
        return false
    }

    fun canonicalsHeldByKeyCode(keyCode: Int): List<CanonicalButton> {
        val out = mutableListOf<CanonicalButton>()
        for ((canonical, bindings) in mapping.bindings) {
            if (canonical !in pressed) continue
            val asserts = bindings.any { it is InputBinding.Button && it.keyCode == keyCode }
            if (asserts) out += canonical
        }
        return out
    }

    fun snapshot(): PortSnapshot = PortSnapshot(
        pressed = pressed.toSet(),
        analog = analog.toMap(),
    )

    fun resetState(): List<CanonicalEvent> {
        val deltas = pressed.map { CanonicalEvent.Released(it) }
        pressed.clear()
        asserters.clear()
        analog.clear()
        return deltas
    }

    private inline fun forEachBinding(action: (CanonicalButton, InputBinding) -> Unit) {
        for ((canonical, bindings) in mapping.bindings) {
            for (binding in bindings) action(canonical, binding)
        }
    }

    private fun directionOf(binding: InputBinding.Axis): Int =
        sign(binding.activeMax - binding.restingValue).toInt()

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
