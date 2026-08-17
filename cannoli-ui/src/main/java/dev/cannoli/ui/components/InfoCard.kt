package dev.cannoli.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cannoli.ui.theme.ErrorHighlight
import dev.cannoli.ui.theme.LocalCannoliColors
import dev.cannoli.ui.theme.LocalCannoliTypography
import dev.cannoli.ui.theme.Spacing
import dev.cannoli.ui.theme.Success
import dev.cannoli.ui.theme.Warning

enum class InfoStatus { OK, WARNING, ERROR }

data class InfoRowItem(
    val label: String,
    val value: String,
    val muted: Boolean = false,
    val status: InfoStatus? = null,
)

@Composable
fun InfoCard(items: List<InfoRowItem>, modifier: Modifier = Modifier) {
    val colors = LocalCannoliColors.current
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .background(colors.text.copy(alpha = 0.05f), shape)
            .border(1.dp, colors.text.copy(alpha = 0.20f), shape),
    ) {
        items.forEachIndexed { index, item ->
            if (index > 0) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(colors.text.copy(alpha = 0.12f)),
                )
            }
            InfoCardRow(item)
        }
    }
}

@Composable
private fun InfoCardRow(item: InfoRowItem) {
    val typo = LocalCannoliTypography.current
    val colors = LocalCannoliColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.Md, vertical = Spacing.Sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = item.label.uppercase(),
            style = typo.labelSmall.copy(color = colors.text.copy(alpha = 0.55f), letterSpacing = 1.sp),
        )
        Spacer(modifier = Modifier.width(Spacing.Md))
        Text(
            text = item.value,
            style = typo.bodyMedium.copy(color = if (item.muted) colors.text.copy(alpha = 0.45f) else colors.text),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f),
        )
        item.status?.let { status ->
            Spacer(modifier = Modifier.width(Spacing.Sm))
            Box(modifier = Modifier.size(8.dp).background(statusColor(status), CircleShape))
        }
    }
}

private fun statusColor(status: InfoStatus): Color = when (status) {
    InfoStatus.OK -> Success
    InfoStatus.WARNING -> Warning
    InfoStatus.ERROR -> ErrorHighlight
}
