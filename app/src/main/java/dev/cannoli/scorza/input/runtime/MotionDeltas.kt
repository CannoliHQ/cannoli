package dev.cannoli.scorza.input.runtime

object MotionDeltas {

    /**
     * Returns true when the deltas list contains a digital press or release, false when it
     * contains only AnalogChanged (or is empty). LibretroActivity.dispatchGenericMotionEvent
     * uses this to decide whether to return true (we consumed the motion) or defer to
     * super.dispatchGenericMotionEvent so the framework can synthesize KEYCODE_DPAD_* from
     * unbound HAT axes. Without the digital-only filter, AnalogChanged from a bound stick
     * blocks synthesis on devices that also emit unbound HAT axes in the same MotionEvent.
     */
    fun anyDigitalChange(deltas: List<CanonicalEvent>): Boolean =
        deltas.any { it is CanonicalEvent.Pressed || it is CanonicalEvent.Released }
}
