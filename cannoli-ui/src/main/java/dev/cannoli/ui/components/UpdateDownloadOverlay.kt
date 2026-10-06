package dev.cannoli.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.cannoli.ui.ButtonStyle
import dev.cannoli.ui.R

@Composable
fun UpdateDownloadOverlay(
    versionName: String,
    changelog: String,
    progress: Float,
    error: String?,
    buttonStyle: ButtonStyle = ButtonStyle()
) {
    ProgressOverlay(
        title = "v$versionName",
        subtitle = changelog,
        progress = progress,
        error = error,
        buttonStyle = buttonStyle,
        retryLabel = stringResource(R.string.label_retry),
    )
}
