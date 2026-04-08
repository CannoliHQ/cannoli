package dev.cannoli.scorza.ui.components

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
import androidx.compose.ui.unit.sp
import dev.cannoli.scorza.R
import dev.cannoli.igm.ButtonLabelSet
import dev.cannoli.igm.ui.components.BottomBar
import dev.cannoli.igm.ui.components.screenPadding
import dev.cannoli.igm.ui.theme.LocalCannoliFont

@Composable
fun UpdateDownloadOverlay(
    versionName: String,
    changelog: String,
    progress: Float,
    error: String?,
    buttonLabelSet: ButtonLabelSet = ButtonLabelSet.PLUMBER
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

            Spacer(modifier = Modifier.height(16.dp))

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
                Spacer(modifier = Modifier.height(24.dp))
            }

            if (error != null) {
                Text(
                    text = error,
                    style = TextStyle(
                        fontFamily = LocalCannoliFont.current,
                        fontSize = 16.sp,
                        color = Color(0xFFFF6B6B),
                        textAlign = TextAlign.Center
                    )
                )
            } else {
                LinearProgressIndicator(
                    progress = { progress.coerceAtLeast(0f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = Color.White,
                    trackColor = Color(0xFF333333),
                    gapSize = 0.dp,
                    drawStopIndicator = {}
                )
                Spacer(modifier = Modifier.height(8.dp))
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
            leftItems = listOf(buttonLabelSet.back to stringResource(R.string.label_cancel)),
            rightItems = if (error != null) listOf(buttonLabelSet.confirm to stringResource(R.string.update_retry)) else emptyList()
        )
    }
}
