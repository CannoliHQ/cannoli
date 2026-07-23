package dev.cannoli.scorza.libretro

import dev.cannoli.ui.computeScreenGeometryRect

data class ViewportRect(val x: Int, val y: Int, val w: Int, val h: Int)

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
    val w = frameWidth
    val h = frameHeight
    val rotated = rotation == 1 || rotation == 3
    val gameAspect = when (scalingMode) {
        ScalingMode.FULLSCREEN -> surfaceWidth.toFloat() / surfaceHeight.toFloat()
        ScalingMode.CORE_REPORTED -> {
            val base = if (coreAspectRatio > 0f) coreAspectRatio else w.toFloat() / h.toFloat()
            if (rotated) 1f / base else base
        }
        ScalingMode.ASPECT_SCREEN -> {
            val base = w.toFloat() / h.toFloat()
            if (rotated) 1f / base else base
        }
        ScalingMode.INTEGER,
        ScalingMode.INTEGER_OVERSCALE -> if (rotated) h.toFloat() / w.toFloat() else w.toFloat() / h.toFloat()
    }
    val region = computeScreenGeometryRect(
        surfaceWidth, surfaceHeight,
        geometryWidthPct, geometryHeightPct, geometryXPct, geometryYPct,
    )
    val portrait = surfaceWidth < surfaceHeight
    val pm = if (portrait && portraitMarginPx > 0) portraitMarginPx else 0
    val effLeft = region.x
    val effW = region.w.coerceAtLeast(1)
    val effH = (region.h - pm).coerceAtLeast(1)
    val regionBottomGl = surfaceHeight - (region.y + region.h)
    val effBottom = regionBottomGl + pm
    val screenAspect = effW.toFloat() / effH.toFloat()

    val vpW: Int
    val vpH: Int
    if (scalingMode == ScalingMode.INTEGER || scalingMode == ScalingMode.INTEGER_OVERSCALE) {
        val dimW = if (rotated) h else w
        val dimH = if (rotated) w else h
        val scaleXf = effW.toFloat() / dimW
        val scaleYf = effH.toFloat() / dimH
        val minF = minOf(scaleXf, scaleYf)
        val scale = if (scalingMode == ScalingMode.INTEGER_OVERSCALE) {
            maxOf(1, kotlin.math.ceil(minF).toInt())
        } else {
            maxOf(1, kotlin.math.floor(minF).toInt())
        }
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
    val vpX = effLeft + (effW - outW) / 2
    val vpY = effBottom + (effH - outH) / 2
    return ViewportRect(vpX, vpY, outW, outH)
}
