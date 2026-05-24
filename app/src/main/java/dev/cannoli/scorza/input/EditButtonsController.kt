package dev.cannoli.scorza.input

import dagger.hilt.android.scopes.ActivityScoped
import dev.cannoli.scorza.input.AnalogRole
import dev.cannoli.scorza.input.CanonicalButton
import dev.cannoli.scorza.input.DeviceMapping
import dev.cannoli.scorza.input.HatDirection
import dev.cannoli.scorza.input.InputBinding
import dev.cannoli.scorza.input.repo.MappingRepository
import dev.cannoli.scorza.input.runtime.ActiveMappingHolder
import dev.cannoli.scorza.input.runtime.PortRouter
import javax.inject.Inject
import kotlin.math.abs

@ActivityScoped
class EditButtonsController @Inject constructor(
    private val repository: MappingRepository,
    private val portRouter: PortRouter,
    private val activeMappingHolder: ActiveMappingHolder,
) {
    var clock: () -> Long = { System.currentTimeMillis() }
    companion object {
        const val CAPTURE_WINDOW_MS = 150L
        const val CAPTURE_TIMEOUT_MS = 5000L
        private const val AXIS_DETECT_THRESHOLD = 0.6f
    }

    private var pendingMapping: DeviceMapping? = null
    private var pendingCanonical: CanonicalButton? = null
    private var startedAtMillis: Long = 0
    private var firstEventAtMillis: Long = -1
    private val capturedKeys = linkedSetOf<Int>()
    private val capturedAxes = linkedMapOf<Int, Float>()

    val isListening: Boolean get() = pendingCanonical != null

    fun startListening(mapping: DeviceMapping, canonical: CanonicalButton) {
        pendingMapping = mapping
        pendingCanonical = canonical
        startedAtMillis = clock()
        firstEventAtMillis = -1
        capturedKeys.clear()
        capturedAxes.clear()
        dev.cannoli.scorza.util.InputLog.write("[edit] startListening mapping=${mapping.id} canonical=$canonical")
    }

    fun cancelListening() {
        pendingMapping = null
        pendingCanonical = null
        firstEventAtMillis = -1
        capturedKeys.clear()
        capturedAxes.clear()
    }

    fun captureRawKeyEvent(keyCode: Int) {
        if (pendingCanonical == null) return
        if (keyCode == android.view.KeyEvent.KEYCODE_UNKNOWN) return
        if (firstEventAtMillis < 0) firstEventAtMillis = clock()
        capturedKeys.add(keyCode)
        dev.cannoli.scorza.util.InputLog.write("[edit] captureRawKeyEvent keyCode=$keyCode firstAt=$firstEventAtMillis now=${clock()}")
    }

    fun captureRawAxisEvent(axisValues: Map<Int, Float>) {
        if (pendingCanonical == null) return
        for ((axis, value) in axisValues) {
            if (abs(value) < AXIS_DETECT_THRESHOLD) continue
            val prev = capturedAxes[axis] ?: 0f
            if (abs(value) > abs(prev)) capturedAxes[axis] = value
            if (firstEventAtMillis < 0) firstEventAtMillis = clock()
        }
    }

    fun tickAndMaybeFinalize(): DeviceMapping? {
        val canonical = pendingCanonical ?: return null
        val mapping = pendingMapping ?: return null
        val now = clock()

        if (firstEventAtMillis < 0 && now - startedAtMillis >= CAPTURE_TIMEOUT_MS) {
            dev.cannoli.scorza.util.InputLog.write("[edit] tick TIMEOUT canonical=$canonical")
            cancelListening()
            return null
        }
        if (firstEventAtMillis >= 0 && now - firstEventAtMillis >= CAPTURE_WINDOW_MS) {
            dev.cannoli.scorza.util.InputLog.write("[edit] tick FINALIZE canonical=$canonical keys=$capturedKeys axes=$capturedAxes")
            return finalize(mapping, canonical)
        }
        return null
    }

    private fun finalize(mapping: DeviceMapping, canonical: CanonicalButton): DeviceMapping {
        val bindings = mutableListOf<InputBinding>()
        for (key in capturedKeys) bindings.add(InputBinding.Button(key))
        for ((axis, peak) in capturedAxes) {
            val isHatLike = (axis == 15 || axis == 16) && (peak == -1f || peak == 1f)
            if (isHatLike) {
                val direction = when {
                    axis == 15 && peak < 0 -> HatDirection.LEFT
                    axis == 15 && peak > 0 -> HatDirection.RIGHT
                    axis == 16 && peak < 0 -> HatDirection.UP
                    else -> HatDirection.DOWN
                }
                bindings.add(InputBinding.Hat(axis = axis, direction = direction))
            } else {
                val activeMax = if (peak >= 0) 1f else -1f
                bindings.add(
                    InputBinding.Axis(
                        axis = axis,
                        restingValue = 0f,
                        activeMin = 0f,
                        activeMax = activeMax,
                        digitalThreshold = 0.5f,
                        invert = false,
                        analogRole = AnalogRole.DIGITAL_BUTTON,
                    )
                )
            }
        }

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

        // Promote source to USER_WIZARD on actual binding changes so the resolver can
        // distinguish "user customized buttons" from "cosmetic edit on an ANDROID_DEFAULT
        // fallback." Without this, an ANDROID_DEFAULT mapping with a cosmetic toggle blocks
        // bundled RA cfg matches on the next resolve.
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
