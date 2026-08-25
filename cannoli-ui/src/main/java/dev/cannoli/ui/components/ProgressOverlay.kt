package dev.cannoli.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.cannoli.ui.ButtonStyle
import dev.cannoli.ui.R
import dev.cannoli.ui.theme.ErrorText
import dev.cannoli.ui.theme.LocalCannoliTypography
import dev.cannoli.ui.theme.Spacing

/**
 * A long job holding the screen: what is happening, how far along, and a way out.
 *
 * [title] is the thing being worked on and [subtitle] the standing beneath it. A null [progress]
 * runs the bar indeterminate, for a phase whose size is not yet known. [error] replaces the bar,
 * since a failed job has no progress to report.
 *
 * Back always cancels. A job that cannot be cancelled should not be using an overlay that offers
 * it, and every job that holds the whole screen for minutes should be cancellable.
 */
@Composable
fun ProgressOverlay(
    title: String,
    subtitle: String,
    progress: Float?,
    error: String?,
    buttonStyle: ButtonStyle = ButtonStyle(),
    cancelLabel: String = stringResource(R.string.label_cancel),
    retryLabel: String? = null,
) {
    val typo = LocalCannoliTypography.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 480.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(text = title, style = typo.titleLarge.copy(color = Color.White))

            Spacer(modifier = Modifier.height(Spacing.Md))

            if (subtitle.isNotEmpty()) {
                Text(
                    text = subtitle,
                    style = typo.bodyMedium.copy(color = Color.White, textAlign = TextAlign.Center)
                )
                Spacer(modifier = Modifier.height(Spacing.Lg))
            }

            if (error != null) {
                Text(
                    text = error,
                    style = typo.bodyMedium.copy(color = ErrorText, textAlign = TextAlign.Center)
                )
            } else {
                CannoliProgressBar(progress = progress)
                if (progress != null) {
                    Spacer(modifier = Modifier.height(Spacing.Sm))
                    Text(
                        text = "${(progress * 100).toInt()}%",
                        style = typo.labelSmall.copy(color = Color.White)
                    )
                }
            }
        }
        BottomBar(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(screenPadding),
            leftItems = listOf(buttonStyle.back to cancelLabel),
            rightItems = if (error != null && retryLabel != null) {
                listOf(buttonStyle.confirm to retryLabel)
            } else emptyList()
        )
    }
}
