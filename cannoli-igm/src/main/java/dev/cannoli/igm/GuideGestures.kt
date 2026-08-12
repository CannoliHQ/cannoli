package dev.cannoli.igm

enum class HorizontalGesture { PAN, PAGE }

object GuideGestures {

    const val SWIPE_THRESHOLD_PX = 120f
    const val ZOOM_IN_AT = 1.25f
    const val ZOOM_OUT_AT = 0.8f

    // Mirrors GuideInputHandler.onLeft/onRight, which pans only for a zoomed non-text guide.
    // Keeping the two in step means a swipe never means one thing at zoom 1 and another at zoom 2.
    fun horizontalGesture(guideType: GuideType, textZoom: Int): HorizontalGesture =
        if (guideType != GuideType.TXT && textZoom > 1) HorizontalGesture.PAN else HorizontalGesture.PAGE

    fun pageStep(accumulatedDragX: Float, threshold: Float = SWIPE_THRESHOLD_PX): Int = when {
        accumulatedDragX <= -threshold -> 1
        accumulatedDragX >= threshold -> -1
        else -> 0
    }

    fun zoomStep(accumulatedScale: Float): Int = when {
        accumulatedScale >= ZOOM_IN_AT -> 1
        accumulatedScale <= ZOOM_OUT_AT -> -1
        else -> 0
    }

    // Buttons wrap at the top; pinch clamps, because pinching further out should not snap to 1x.
    fun nextZoom(current: Int, step: Int): Int = (current + step).coerceIn(1, GuideZoom.levels)
}
