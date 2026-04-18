package dev.cannoli.scorza.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import dev.cannoli.ui.theme.LocalCannoliColors

@Composable
fun PortraitMarginOverlay(marginPx: Int) {
    if (marginPx <= 0) return
    val density = LocalDensity.current
    val colors = LocalCannoliColors.current
    Box(modifier = Modifier.fillMaxSize()) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val m = marginPx.toFloat().coerceIn(0f, h)
            val lineY = h - m
            val bandColor = colors.accent.copy(alpha = 0.35f)
            val hatchSpacing = with(density) { 10.dp.toPx() }
            val hatchStroke = with(density) { 1.dp.toPx() }
            var x = -h
            while (x < w + m) {
                drawLine(
                    color = bandColor,
                    start = Offset(x, h),
                    end = Offset(x + m, lineY),
                    strokeWidth = hatchStroke
                )
                x += hatchSpacing
            }
            drawLine(
                color = colors.accent,
                start = Offset(0f, lineY),
                end = Offset(w, lineY),
                strokeWidth = with(density) { 2.dp.toPx() },
                pathEffect = PathEffect.dashPathEffect(
                    floatArrayOf(with(density) { 6.dp.toPx() }, with(density) { 4.dp.toPx() })
                )
            )
        }
    }
}
