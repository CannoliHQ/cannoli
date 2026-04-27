package dev.cannoli.scorza.input.v2

sealed interface InputBinding {

    data class Button(
        val keyCode: Int,
    ) : InputBinding

    data class Hat(
        val axis: Int,
        val direction: HatDirection,
        val threshold: Float = 0.5f,
    ) : InputBinding {
        fun isPressed(rawAxisValue: Float): Boolean = when (direction) {
            HatDirection.UP -> rawAxisValue <= -threshold
            HatDirection.DOWN -> rawAxisValue >= threshold
            HatDirection.LEFT -> rawAxisValue <= -threshold
            HatDirection.RIGHT -> rawAxisValue >= threshold
        }
    }

    data class Axis(
        val axis: Int,
        val restingValue: Float,
        val activeMin: Float,
        val activeMax: Float,
        val digitalThreshold: Float,
        val invert: Boolean = false,
        val analogRole: AnalogRole = AnalogRole.DIGITAL_BUTTON,
    ) : InputBinding {

        fun normalize(rawAxisValue: Float): Float {
            val span = activeMax - restingValue
            if (span == 0f) return 0f
            val raw = (rawAxisValue - restingValue) / span
            val clamped = raw.coerceIn(0f, 1f)
            return if (invert) 1f - clamped else clamped
        }

        fun isDigitalPressed(rawAxisValue: Float): Boolean =
            normalize(rawAxisValue) >= digitalThreshold
    }
}
