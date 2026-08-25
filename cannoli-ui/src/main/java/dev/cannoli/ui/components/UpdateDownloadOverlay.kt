package dev.cannoli.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.cannoli.ui.ButtonStyle
import dev.cannoli.ui.R

/**
 * Downloading a Cannoli release. The shape is [ProgressOverlay]; this names the parts for an app
 * update, so the version reads as a version and the changelog sits where the standing line goes.
 */
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
        retryLabel = stringResource(R.string.update_retry),
    )
}
