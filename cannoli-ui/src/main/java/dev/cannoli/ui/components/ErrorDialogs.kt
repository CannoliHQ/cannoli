package dev.cannoli.ui.components

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.cannoli.ui.ButtonStyle
import dev.cannoli.ui.R
import dev.cannoli.ui.theme.LocalCannoliColors

/**
 * One screen for every way a game fails to start: a core or app that is absent, a platform with
 * nothing mapped, a required BIOS that is not there, or a file that would not read.
 *
 * Three parts, none repeating another. [title] is the problem itself rather than a generic heading,
 * so the first line already says what went wrong. [subject] names only what it went wrong with, a
 * platform and the thing that is missing, never a sentence. The remedy is left to the legend, which
 * states it once: [confirmLabel] when there is something to do, Close alone when there is not.
 */
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
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = Color.White,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = subject,
            style = MaterialTheme.typography.bodyLarge,
            color = LocalCannoliColors.current.text.copy(alpha = 0.6f),
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
