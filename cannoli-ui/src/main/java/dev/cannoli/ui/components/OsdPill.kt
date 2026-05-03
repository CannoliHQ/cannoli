package dev.cannoli.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cannoli.ui.theme.LocalCannoliColors
import dev.cannoli.ui.theme.Radius

enum class OsdPosition {
    TopStart,
    TopCenter,
    TopEnd,
    CenterStart,
    Center,
    CenterEnd,
    BottomStart,
    BottomCenter,
    BottomEnd,
}

@Composable
fun BoxScope.OsdPill(
    message: String,
    position: OsdPosition = OsdPosition.TopCenter,
    edgePadding: androidx.compose.ui.unit.Dp = 50.dp,
) {
    val colors = LocalCannoliColors.current
    Box(
        modifier = Modifier
            .align(position.alignment())
            .padding(position.edgePadding(edgePadding))
            .clip(Radius.Pill)
            .background(colors.highlight)
            .padding(horizontal = 16.dp, vertical = 6.dp)
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
            color = colors.highlightText
        )
    }
}

private fun OsdPosition.alignment(): Alignment = when (this) {
    OsdPosition.TopStart -> Alignment.TopStart
    OsdPosition.TopCenter -> Alignment.TopCenter
    OsdPosition.TopEnd -> Alignment.TopEnd
    OsdPosition.CenterStart -> Alignment.CenterStart
    OsdPosition.Center -> Alignment.Center
    OsdPosition.CenterEnd -> Alignment.CenterEnd
    OsdPosition.BottomStart -> Alignment.BottomStart
    OsdPosition.BottomCenter -> Alignment.BottomCenter
    OsdPosition.BottomEnd -> Alignment.BottomEnd
}

private fun OsdPosition.edgePadding(edge: androidx.compose.ui.unit.Dp): androidx.compose.foundation.layout.PaddingValues {
    val zero = 0.dp
    return androidx.compose.foundation.layout.PaddingValues(
        start = if (this == OsdPosition.TopStart || this == OsdPosition.CenterStart || this == OsdPosition.BottomStart) edge else zero,
        end = if (this == OsdPosition.TopEnd || this == OsdPosition.CenterEnd || this == OsdPosition.BottomEnd) edge else zero,
        top = if (this == OsdPosition.TopStart || this == OsdPosition.TopCenter || this == OsdPosition.TopEnd) edge else zero,
        bottom = if (this == OsdPosition.BottomStart || this == OsdPosition.BottomCenter || this == OsdPosition.BottomEnd) edge else zero,
    )
}
