package dev.cannoli.ui

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
