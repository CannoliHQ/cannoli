package dev.cannoli.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp

data class ScreenRect(val x: Int, val y: Int, val w: Int, val h: Int)

fun computeScreenGeometryRect(
    surfaceWidth: Int,
    surfaceHeight: Int,
    widthPct: Int,
    heightPct: Int,
    xPct: Int,
    yPct: Int,
): ScreenRect {
    val wPct = widthPct.coerceIn(50, 100)
    val hPct = heightPct.coerceIn(50, 100)
    val w = (surfaceWidth * wPct / 100).coerceAtLeast(1)
    val h = (surfaceHeight * hPct / 100).coerceAtLeast(1)
    val maxXPct = (100 - wPct) / 2
    val maxYPct = (100 - hPct) / 2
    val cx = xPct.coerceIn(-maxXPct, maxXPct)
    val cy = yPct.coerceIn(-maxYPct, maxYPct)
    val x = (surfaceWidth - w) / 2 + surfaceWidth * cx / 100
    val y = (surfaceHeight - h) / 2 + surfaceHeight * cy / 100
    return ScreenRect(x, y, w, h)
}

/**
 * Turns the Screen Geometry region into padding around an overlay's content, so the overlay's
 * own edges line up with the game's rather than the panel's. Computed at pixel scale, matching
 * the game viewport: computeScreenGeometryRect divides with integer truncation, and dividing at
 * dp scale rounds to a different pixel than dividing at pixel scale, leaving a sliver of gap at
 * the region's edge. Falls back to the dp-derived region when the surface's pixel size isn't
 * available yet (an early composition pass, before the view is laid out), so a zero-sized surface
 * can't produce a garbage region.
 */
fun computeScreenGeometryPadding(
    surfaceWidthPx: Int,
    surfaceHeightPx: Int,
    surfaceWidthDp: Int,
    surfaceHeightDp: Int,
    widthPct: Int,
    heightPct: Int,
    xPct: Int,
    yPct: Int,
    portraitMarginPx: Int,
    portrait: Boolean,
    density: Density,
): PaddingValues {
    val bottomMarginPx = if (portrait) portraitMarginPx else 0
    return if (surfaceWidthPx > 0 && surfaceHeightPx > 0) {
        val rect = computeScreenGeometryRect(surfaceWidthPx, surfaceHeightPx, widthPct, heightPct, xPct, yPct)
        with(density) {
            PaddingValues(
                start = rect.x.toDp(),
                top = rect.y.toDp(),
                end = (surfaceWidthPx - rect.x - rect.w).coerceAtLeast(0).toDp(),
                bottom = ((surfaceHeightPx - rect.y - rect.h).coerceAtLeast(0) + bottomMarginPx).toDp(),
            )
        }
    } else {
        val rect = computeScreenGeometryRect(surfaceWidthDp, surfaceHeightDp, widthPct, heightPct, xPct, yPct)
        with(density) {
            PaddingValues(
                start = rect.x.dp,
                top = rect.y.dp,
                end = (surfaceWidthDp - rect.x - rect.w).coerceAtLeast(0).dp,
                bottom = (surfaceHeightDp - rect.y - rect.h).coerceAtLeast(0).dp + bottomMarginPx.toDp(),
            )
        }
    }
}
