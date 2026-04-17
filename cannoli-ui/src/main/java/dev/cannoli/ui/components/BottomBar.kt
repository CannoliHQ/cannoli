package dev.cannoli.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cannoli.ui.theme.LocalCannoliColors
import dev.cannoli.ui.theme.Radius
import dev.cannoli.ui.theme.LocalCannoliFont
import dev.cannoli.ui.theme.LocalScaleFactor

@Composable
fun LegendPill(button: String, label: String) {
    val accent = LocalCannoliColors.current.accent
    val outerPill = accent.copy(alpha = 0.15f)
    val innerPill = accent.copy(alpha = 0.30f)
    val sf = LocalScaleFactor.current

    Row(
        modifier = Modifier
            .clip(Radius.Pill)
            .background(outerPill)
            .padding(start = (5 * sf).dp, end = (14 * sf).dp, top = (6 * sf).dp, bottom = (6 * sf).dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy((8 * sf).dp)
    ) {
        Box(
            modifier = Modifier
                .clip(Radius.Pill)
                .background(innerPill)
                .padding(horizontal = (10 * sf).dp, vertical = (4 * sf).dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = button,
                style = TextStyle(
                    fontFamily = LocalCannoliFont.current,
                    fontWeight = FontWeight.Bold,
                    fontSize = (14 * sf).sp,
                    lineHeight = (14 * sf).sp,
                    platformStyle = PlatformTextStyle(includeFontPadding = false),
                    lineHeightStyle = LineHeightStyle(
                        alignment = LineHeightStyle.Alignment.Center,
                        trim = LineHeightStyle.Trim.Both
                    ),
                    color = accent
                )
            )
        }

        Text(
            text = label,
            style = TextStyle(
                fontFamily = LocalCannoliFont.current,
                fontWeight = FontWeight.Bold,
                fontSize = (12 * sf).sp,
                lineHeight = (12 * sf).sp,
                platformStyle = PlatformTextStyle(includeFontPadding = false),
                lineHeightStyle = LineHeightStyle(
                    alignment = LineHeightStyle.Alignment.Center,
                    trim = LineHeightStyle.Trim.Both
                ),
                color = accent
            )
        )
    }
}

@Composable
fun BottomBar(
    modifier: Modifier = Modifier,
    leftItems: List<Pair<String, String>>,
    rightItems: List<Pair<String, String>>
) {
    val sf = LocalScaleFactor.current
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy((8 * sf).dp)) {
            leftItems.forEach { (button, label) ->
                LegendPill(button = button, label = label)
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        Row(horizontalArrangement = Arrangement.spacedBy((8 * sf).dp)) {
            rightItems.forEach { (button, label) ->
                LegendPill(button = button, label = label)
            }
        }
    }
}
