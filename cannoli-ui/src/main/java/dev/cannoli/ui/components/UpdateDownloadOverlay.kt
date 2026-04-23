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

@Composable
fun UpdateDownloadOverlay(
    versionName: String,
    changelog: String,
    progress: Float,
    error: String?,
    buttonStyle: ButtonStyle = ButtonStyle()
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
            Text(
                text = "v$versionName",
                style = typo.titleLarge.copy(color = Color.White)
            )

            Spacer(modifier = Modifier.height(Spacing.Md))

            if (changelog.isNotEmpty()) {
                Text(
                    text = changelog,
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
                Spacer(modifier = Modifier.height(Spacing.Sm))
                Text(
                    text = "${(progress * 100).toInt()}%",
                    style = typo.labelSmall.copy(color = Color.White)
                )
            }
        }
        BottomBar(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(screenPadding),
            leftItems = listOf(buttonStyle.back to stringResource(R.string.label_cancel)),
            rightItems = if (error != null) listOf(buttonStyle.confirm to stringResource(R.string.update_retry)) else emptyList()
        )
    }
}
