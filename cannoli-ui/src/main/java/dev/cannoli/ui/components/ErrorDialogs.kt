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
 * One screen for every way a game can fail to reach an emulator: the core is absent, the standalone
 * app is absent, or the platform has no emulator at all. They read as the same problem to the person
 * holding the device, and the fix is the same screen, so they are not three dialogs.
 *
 * [problem] names what is missing and is built by the caller, which knows which failure this is.
 */
@Composable
fun MissingEmulatorDialog(
    problem: String,
    showRemove: Boolean = false,
    showChangeEmulator: Boolean = false,
    buttonStyle: ButtonStyle = ButtonStyle()
) {
    OverlayScrim(
        bottomBar = {
            val confirmLabel = when {
                showRemove -> stringResource(R.string.label_remove)
                showChangeEmulator -> stringResource(R.string.label_change_emulator)
                else -> null
            }
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
            text = stringResource(R.string.dialog_title_missing_emulator),
            style = MaterialTheme.typography.titleLarge,
            color = Color.White,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(12.dp))
        // The hint rides with every case: knowing what is missing is no use without knowing where
        // to fix it, and the standalone screen used to say only the former.
        Text(
            text = problem + "\n" + stringResource(R.string.dialog_missing_emulator_hint),
            style = MaterialTheme.typography.bodyLarge,
            color = LocalCannoliColors.current.text.copy(alpha = 0.6f),
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
fun LaunchErrorDialog(message: String, buttonStyle: ButtonStyle = ButtonStyle()) {
    OverlayScrim(
        bottomBar = { LegendPill(button = buttonStyle.back, label = stringResource(R.string.label_close)) }
    ) {
        Text(
            text = stringResource(R.string.dialog_title_launch_error),
            style = MaterialTheme.typography.titleLarge,
            color = Color.White
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = LocalCannoliColors.current.text.copy(alpha = 0.6f)
        )
    }
}
