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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.cannoli.ui.theme.ErrorText
import dev.cannoli.ui.theme.ProgressTrack
import dev.cannoli.ui.theme.Spacing
import dev.cannoli.ui.theme.Radius
import androidx.compose.ui.unit.sp
import dev.cannoli.ui.R
import dev.cannoli.ui.ButtonStyle
import dev.cannoli.ui.components.BottomBar
import dev.cannoli.ui.components.screenPadding
import dev.cannoli.ui.theme.LocalCannoliFont

@Composable
fun UpdateDownloadOverlay(
    versionName: String,
    changelog: String,
    progress: Float,
    error: String?,
    buttonStyle: ButtonStyle = ButtonStyle()
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.6f),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "v$versionName",
                style = TextStyle(
                    fontFamily = LocalCannoliFont.current,
                    fontWeight = FontWeight.Bold,
                    fontSize = 28.sp,
                    color = Color.White
                )
            )

            Spacer(modifier = Modifier.height(Spacing.Md))

            if (changelog.isNotEmpty()) {
                Text(
                    text = changelog,
                    style = TextStyle(
                        fontFamily = LocalCannoliFont.current,
                        fontSize = 16.sp,
                        color = Color.White,
                        textAlign = TextAlign.Center
                    )
                )
                Spacer(modifier = Modifier.height(Spacing.Lg))
            }

            if (error != null) {
                Text(
                    text = error,
                    style = TextStyle(
                        fontFamily = LocalCannoliFont.current,
                        fontSize = 16.sp,
                        color = ErrorText,
                        textAlign = TextAlign.Center
                    )
                )
            } else {
                LinearProgressIndicator(
                    progress = { progress.coerceAtLeast(0f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(Radius.Sm)),
                    color = Color.White,
                    trackColor = ProgressTrack,
                    gapSize = 0.dp,
                    drawStopIndicator = {}
                )
                Spacer(modifier = Modifier.height(Spacing.Sm))
                Text(
                    text = "${(progress * 100).toInt()}%",
                    style = TextStyle(
                        fontFamily = LocalCannoliFont.current,
                        fontSize = 14.sp,
                        color = Color.White
                    )
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
