package dev.cannoli.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import dev.cannoli.ui.theme.LocalCannoliColors

@Composable
fun HintRow(
    text: String,
    fontSize: TextUnit,
    lineHeight: TextUnit,
    verticalPadding: Dp,
) {
    val colors = LocalCannoliColors.current
    Box(
        modifier = Modifier
            .height(pillItemHeight(lineHeight, verticalPadding))
            .padding(horizontal = 14.dp, vertical = verticalPadding),
        contentAlignment = Alignment.CenterStart
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge.copy(
                fontSize = fontSize,
                lineHeight = lineHeight,
                color = colors.text.copy(alpha = 0.6f)
            )
        )
    }
}
