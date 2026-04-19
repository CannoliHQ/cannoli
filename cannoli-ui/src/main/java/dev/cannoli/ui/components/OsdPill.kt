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

@Composable
fun BoxScope.OsdPill(
    message: String,
    bottomPadding: androidx.compose.ui.unit.Dp = 25.dp
) {
    val colors = LocalCannoliColors.current
    Box(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(bottom = bottomPadding)
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
