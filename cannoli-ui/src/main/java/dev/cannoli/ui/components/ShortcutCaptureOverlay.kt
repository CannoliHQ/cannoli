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
                    color = colors.text,
                ),
            )
            Spacer(modifier = Modifier.height(Spacing.Lg))
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
