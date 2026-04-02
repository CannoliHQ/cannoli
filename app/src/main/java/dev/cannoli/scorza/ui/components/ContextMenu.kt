package dev.cannoli.scorza.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.cannoli.scorza.R
import dev.cannoli.scorza.settings.ButtonLabelSet

/**
 * Full-screen dark overlay container with centered content and a bottom bar.
 */
@Composable
fun OverlayScrim(
    bottomBar: @Composable () -> Unit = {},
    content: @Composable () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(24.dp)
        ) {
            content()
        }

        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(start = screenPadding, end = screenPadding, bottom = screenPadding)
        ) {
            bottomBar()
        }
    }
}

/**
 * Overlay that shows a simple message with a B-to-close bottom bar.
 */
@Composable
fun MessageOverlay(
    message: String,
    buttonLabelSet: ButtonLabelSet = ButtonLabelSet.PLUMBER,
    buttonLabel: String = stringResource(R.string.label_close)
) {
    OverlayScrim(
        bottomBar = { LegendPill(button = buttonLabelSet.back, label = buttonLabel) }
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = Color.White
        )
    }
}

/**
 * Overlay with a message and A/B confirm/cancel bottom bar.
 */
@Composable
fun ConfirmOverlay(
    message: String,
    buttonLabelSet: ButtonLabelSet = ButtonLabelSet.PLUMBER,
    cancelLabel: String = stringResource(R.string.label_cancel),
    confirmLabel: String = stringResource(R.string.label_delete)
) {
    OverlayScrim(
        bottomBar = {
            BottomBar(
                leftItems = listOf(buttonLabelSet.back to cancelLabel),
                rightItems = listOf(buttonLabelSet.confirm to confirmLabel)
            )
        }
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = Color.White
        )
    }
}
