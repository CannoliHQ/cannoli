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
        val titleStyle = MaterialTheme.typography.titleLarge
        val bodyStyle = MaterialTheme.typography.bodyLarge
        val smallStyle = MaterialTheme.typography.bodyMedium
        // The overlay column does not scroll and centres its content, so anything too tall is
        // clipped at both ends and the title is the first thing lost. The column already reserves
        // the footer, so its constraint is the real budget: measure against it and step the subject
        // down rather than let a long list push the title off the screen.
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
                    color = LocalCannoliColors.current.text.copy(alpha = 0.6f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

/**
 * Line height in px, falling back to 1.3 times the font size where a style leaves `lineHeight`
 * unspecified, which `titleLarge` does. `TextUnit.toPx` throws on anything that is not Sp, so
 * reading `lineHeight` unguarded crashes rather than mis-measuring.
 */
private fun TextStyle.lineHeightPx(density: Density): Float = with(density) {
    lineHeight.takeOrElse { fontSize.takeOrElse { 16.sp } * 1.3f }.toPx()
}
