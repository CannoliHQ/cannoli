package dev.cannoli.ui

import kotlin.math.floor

enum class ScalingMode { CORE_REPORTED, INTEGER, FULLSCREEN }

data class ViewportRect(val x: Int, val y: Int, val w: Int, val h: Int)

/**
 * Screen Geometry gives the region, Portrait Margin shrinks it from the bottom in portrait, and the
 * game is fitted inside what remains. Restored from v1, where the internal runner did the same job.
 * Returns a top-left rect, matching computeScreenGeometryRect.
 */
fun computeViewport(
    surfaceWidth: Int,
    surfaceHeight: Int,
    frameWidth: Int,
    frameHeight: Int,
    coreAspectRatio: Float,
    rotation: Int,
    scalingMode: ScalingMode,
    portraitMarginPx: Int,
    geometryWidthPct: Int,
    geometryHeightPct: Int,
    geometryXPct: Int,
    geometryYPct: Int,
): ViewportRect {
    val rotated = rotation == 1 || rotation == 3
    val region = computeScreenGeometryRect(
        surfaceWidth, surfaceHeight,
        geometryWidthPct, geometryHeightPct, geometryXPct, geometryYPct,
    )
    val portrait = surfaceWidth < surfaceHeight
    val pm = if (portrait && portraitMarginPx > 0) portraitMarginPx else 0

    val effLeft = region.x
    val effW = region.w.coerceAtLeast(1)
    val effH = (region.h - pm).coerceAtLeast(1)
    val effTop = region.y

    if (scalingMode == ScalingMode.FULLSCREEN)
        return ViewportRect(effLeft, effTop, effW, effH)

    val base = if (coreAspectRatio > 0f)
        coreAspectRatio
    else
        frameWidth.toFloat() / frameHeight.toFloat()
    val gameAspect = if (rotated) 1f / base else base
    val screenAspect = effW.toFloat() / effH.toFloat()

    val vpW: Int
    val vpH: Int
    if (scalingMode == ScalingMode.INTEGER) {
        val dimW = if (rotated) frameHeight else frameWidth
        val dimH = if (rotated) frameWidth else frameHeight
        val scale = maxOf(
            1,
            floor(minOf(effW.toFloat() / dimW, effH.toFloat() / dimH)).toInt(),
        )
        vpW = dimW * scale
        vpH = dimH * scale
    } else if (gameAspect > screenAspect) {
        vpW = effW
        vpH = (effW / gameAspect).toInt()
    } else {
        vpW = (effH * gameAspect).toInt()
        vpH = effH
    }

    val outW = vpW.coerceAtLeast(1)
    val outH = vpH.coerceAtLeast(1)
    return ViewportRect(
        effLeft + (effW - outW) / 2,
        effTop + (effH - outH) / 2,
        outW,
        outH,
    )
}
