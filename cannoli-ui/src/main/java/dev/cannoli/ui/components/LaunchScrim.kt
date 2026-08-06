package dev.cannoli.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import dev.cannoli.ui.theme.GrayText
import kotlinx.coroutines.delay

private const val StatusDelayMs = 250L

/**
 * The launcher's ground while a launch is in flight, from the moment a launch is committed until
 * the emulator takes the screen.
 *
 * Black and silent by default. A launch with nothing to report should look like nothing at all
 * rather than a title card, and the only reason this exists is so the launcher is not left sitting
 * on a game list it has stopped responding on.
 *
 * [status] is for work that is genuinely holding the launch up, currently only a save sync round
 * trip. It waits [StatusDelayMs] before appearing, so a check that answers quickly shows nothing
 * and only a wait long enough to look like a hang gets explained.
 */
@Composable
fun LaunchScrim(status: String? = null) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(screenPadding)
    ) {
        if (status != null) {
            var visible by remember(status) { mutableStateOf(false) }
            LaunchedEffect(status) {
                delay(StatusDelayMs)
                visible = true
            }
            if (visible) {
                Text(
                    text = status,
                    modifier = Modifier.align(Alignment.BottomStart),
                    style = MaterialTheme.typography.bodyMedium,
                    color = GrayText,
                )
            }
        }
    }
}
