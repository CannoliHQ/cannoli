package dev.cannoli.scorza.input

import dagger.hilt.android.scopes.ActivityScoped
import dev.cannoli.scorza.input.autoconfig.AutoconfigRepository
import dev.cannoli.scorza.input.runtime.ActiveMappingHolder
import dev.cannoli.scorza.input.runtime.PortRouter
import javax.inject.Inject
import kotlin.math.abs

@ActivityScoped
class EditButtonsController @Inject constructor(
    private val repository: AutoconfigRepository,
    private val portRouter: PortRouter,
    private val activeMappingHolder: ActiveMappingHolder,
) {
    private val capture = BindingCapture()

    var clock: () -> Long
        get() = capture.clock
        set(value) { capture.clock = value }

    companion object {
        const val CAPTURE_WINDOW_MS = BindingCapture.CAPTURE_WINDOW_MS
        const val CAPTURE_TIMEOUT_MS = BindingCapture.CAPTURE_TIMEOUT_MS
    }

    private var pendingMapping: DeviceMapping? = null

    val isListening: Boolean get() = capture.isListening

    fun startListening(mapping: DeviceMapping, canonical: CanonicalButton) {
        pendingMapping = mapping
        capture.start(canonical)
        dev.cannoli.scorza.util.InputLog.write("[edit] startListening mapping=${mapping.id} canonical=$canonical")
    }

    fun cancelListening() {
        pendingMapping = null
        capture.cancel()
    }

    fun captureRawKeyEvent(keyCode: Int) = capture.onKey(keyCode)

    fun captureRawAxisEvent(axisValues: Map<Int, Float>) = capture.onAxis(axisValues)

    fun tickAndMaybeFinalize(): DeviceMapping? {
        val canonical = capture.canonical ?: return null
        val mapping = pendingMapping ?: return null
        return when (val outcome = capture.tick()) {
            null -> null
            BindingCapture.Outcome.TimedOut -> {
                dev.cannoli.scorza.util.InputLog.write("[edit] tick TIMEOUT canonical=$canonical")
                pendingMapping = null
                null
            }
            is BindingCapture.Outcome.Captured -> {
                dev.cannoli.scorza.util.InputLog.write(
                    "[edit] tick FINALIZE canonical=$canonical bindings=${outcome.bindings}"
                )
                finalize(mapping, canonical, outcome.bindings)
            }
        }
    }

    private fun finalize(
        mapping: DeviceMapping,
        canonical: CanonicalButton,
        bindings: List<InputBinding>,
    ): DeviceMapping {
        val oldBindings = mapping.bindings[canonical].orEmpty()
        val newBindings = mapping.bindings.toMutableMap()
        newBindings[canonical] = bindings

        var displacedSlotFilled = false
        for ((other, otherBindings) in mapping.bindings) {
            if (other == canonical) continue
            if (otherBindings.isEmpty()) continue
            val filtered = otherBindings.filterNot { existing ->
                bindings.any { incoming -> sameInput(existing, incoming) }
            }
            if (filtered.size == otherBindings.size) continue
            if (!displacedSlotFilled && oldBindings.isNotEmpty()) {
                newBindings[other] = filtered + oldBindings
                displacedSlotFilled = true
                dev.cannoli.scorza.util.InputLog.write(
                    "[edit] swap canonical=$canonical displaced=$other restored=$oldBindings"
                )
            } else {
                newBindings[other] = filtered
                dev.cannoli.scorza.util.InputLog.write(
                    "[edit] steal canonical=$canonical clearedFrom=$other"
                )
            }
        }

        // Promote source to USER_WIZARD on actual binding changes so the resolver can distinguish
        // "user customized buttons" from "cosmetic edit on an unidentified fallback."
        val saved = mapping.copy(
            bindings = newBindings,
            userEdited = true,
            source = MappingSource.USER_WIZARD,
        )
        repository.save(saved)
        portRouter.updateMapping(saved, rebuildEvaluator = true)
        if (activeMappingHolder.active.value?.id == saved.id) {
            activeMappingHolder.set(saved)
        }
        cancelListening()
        return saved
    }

    private fun sameInput(a: InputBinding, b: InputBinding): Boolean = when {
        a is InputBinding.Button && b is InputBinding.Button -> a.keyCode == b.keyCode
        a is InputBinding.Hat && b is InputBinding.Hat -> a.axis == b.axis && a.direction == b.direction
        a is InputBinding.Axis && b is InputBinding.Axis -> {
            a.axis == b.axis &&
                sameSign(a.activeMax, b.activeMax) &&
                a.analogRole == b.analogRole
        }
        else -> false
    }

    private fun sameSign(x: Float, y: Float): Boolean = (x >= 0f) == (y >= 0f)
}
