package dev.cannoli.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import dev.cannoli.ui.R
import dev.cannoli.ui.theme.LocalCannoliColors
import dev.cannoli.ui.theme.Radius
import dev.cannoli.ui.theme.Spacing

/**
 * The screen shown while a shortcut chord is being held, over whatever list asked for it.
 *
 * Shared so the launcher and the in-game menu capture a chord the same way: the same prompt, the
 * same keys as you hold them, the same bar filling toward the commit. Two of these would be two
 * different answers to how long you have to hold something.
 *
 * Presentation only. [heldText] is already formatted by the caller, which is what keeps this free
 * of any idea of what a shortcut or a keycode is.
 */
@Composable
fun ShortcutCaptureOverlay(
    actionName: String,
    heldText: String?,
    progress: Float,
    fontSize: TextUnit,
) {
    val colors = LocalCannoliColors.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.92f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth(),
        ) {
            Text(
                text = actionName,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontSize = fontSize * 1.1f,
                    color = colors.text,
                ),
            )
            Spacer(modifier = Modifier.height(Spacing.Sm))
            Text(
                text = heldText ?: stringResource(R.string.shortcut_hold_prompt),
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = fontSize * 0.73f,
                    color = colors.text.copy(alpha = 0.6f),
                ),
            )
            Spacer(modifier = Modifier.height(Spacing.Lg))
            // Only once something is held: an empty bar beside the prompt reads as a thing that has
            // stalled rather than one that has not started.
            if (heldText != null) {
                Box(
                    modifier = Modifier
                        .widthIn(max = 280.dp).fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(Radius.Sm))
                        .background(colors.text.copy(alpha = 0.2f)),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(progress.coerceIn(0f, 1f))
                            .height(8.dp)
                            .clip(RoundedCornerShape(Radius.Sm))
                            .background(colors.highlight),
                    )
                }
            }
        }
    }
}
