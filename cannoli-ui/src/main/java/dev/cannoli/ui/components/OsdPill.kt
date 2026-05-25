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
    BottomCenterLow,
    BottomEnd,
}

@Composable
fun BoxScope.OsdPill(
    message: String,
    position: OsdPosition = OsdPosition.TopCenter,
) {
    val colors = LocalCannoliColors.current
    Box(
        modifier = Modifier
            .align(position.alignment())
            .padding(position.edgePadding())
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
    OsdPosition.BottomCenterLow -> Alignment.BottomCenter
    OsdPosition.BottomEnd -> Alignment.BottomEnd
}

// Insets per position. Top/bottom-center get more breathing room (status bar / nav clearance);
// corner anchors hug closer to the edge so they don't read as floating in the middle.
private fun OsdPosition.edgePadding(): androidx.compose.foundation.layout.PaddingValues {
    val zero = 0.dp
    val center = 50.dp
    val centerLow = 12.dp
    val corner = 16.dp
    return when (this) {
        OsdPosition.TopCenter -> androidx.compose.foundation.layout.PaddingValues(top = center)
        OsdPosition.BottomCenter -> androidx.compose.foundation.layout.PaddingValues(bottom = center)
        OsdPosition.BottomCenterLow -> androidx.compose.foundation.layout.PaddingValues(bottom = centerLow)
        OsdPosition.TopStart -> androidx.compose.foundation.layout.PaddingValues(top = corner, start = corner)
        OsdPosition.TopEnd -> androidx.compose.foundation.layout.PaddingValues(top = corner, end = corner)
        OsdPosition.BottomStart -> androidx.compose.foundation.layout.PaddingValues(bottom = corner, start = corner)
        OsdPosition.BottomEnd -> androidx.compose.foundation.layout.PaddingValues(bottom = corner, end = corner)
        OsdPosition.CenterStart -> androidx.compose.foundation.layout.PaddingValues(start = corner)
        OsdPosition.CenterEnd -> androidx.compose.foundation.layout.PaddingValues(end = corner)
        OsdPosition.Center -> androidx.compose.foundation.layout.PaddingValues(zero)
    }
}
