package dev.cannoli.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cannoli.ui.theme.LocalCannoliColors
import dev.cannoli.ui.theme.LocalCannoliIconFont

@Composable
fun SectionHeader(
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
            style = MaterialTheme.typography.bodyMedium.copy(
                fontSize = (fontSize.value * 0.72f).sp,
                lineHeight = lineHeight,
                color = colors.text.copy(alpha = 0.6f)
            )
        )
    }
}

/** A [SectionHeader]-styled notice line with a leading icon, rendered via [LocalCannoliIconFont] so it resolves on every font the user can pick. */
@Composable
fun SectionNotice(
    icon: String,
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
        // One style for both, so the glyph and the sentence resolve identical line metrics and
        // CenterVertically has equal-height boxes to centre. Deriving them separately left the
        // icon on the theme's line height and the text on the caller's.
        val noticeStyle = MaterialTheme.typography.bodyMedium.copy(
            fontSize = (fontSize.value * 0.72f).sp,
            lineHeight = lineHeight,
            color = colors.text.copy(alpha = 0.6f)
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = icon,
                style = noticeStyle.copy(fontFamily = LocalCannoliIconFont.current)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(text = text, style = noticeStyle)
        }
    }
}
