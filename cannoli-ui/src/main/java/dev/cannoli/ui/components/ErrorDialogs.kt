package dev.cannoli.ui.components

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.takeOrElse
import androidx.compose.ui.unit.dp
import dev.cannoli.ui.ButtonStyle
import dev.cannoli.ui.R
import dev.cannoli.ui.theme.LocalCannoliColors

@Composable
fun LaunchIssue(
    title: String,
    subject: String,
    confirmLabel: String? = null,
    buttonStyle: ButtonStyle = ButtonStyle()
) {
    OverlayScrim(
        bottomBar = {
            if (confirmLabel != null) {
                BottomBar(
                    leftItems = listOf(buttonStyle.back to stringResource(R.string.label_close)),
                    rightItems = listOf(buttonStyle.confirm to confirmLabel)
                )
            } else {
                LegendPill(button = buttonStyle.back, label = stringResource(R.string.label_close))
            }
        }
    ) {
        val titleStyle = MaterialTheme.typography.titleLarge
        val bodyStyle = MaterialTheme.typography.bodyLarge
        val smallStyle = MaterialTheme.typography.bodyMedium
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val lines = subject.count { it == '\n' } + 1
            val density = LocalDensity.current
            val fits = (titleStyle.lineHeightPx(density) * 2) +
                with(density) { 12.dp.toPx() } +
                (bodyStyle.lineHeightPx(density) * lines) <= with(density) { maxHeight.toPx() }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = title,
                    style = titleStyle,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = subject,
                    style = if (fits) bodyStyle else smallStyle,
                    color = LocalCannoliColors.current.text,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

private fun TextStyle.lineHeightPx(density: Density): Float = with(density) {
    lineHeight.takeOrElse { fontSize.takeOrElse { 16.sp } * 1.3f }.toPx()
}
