package dev.cannoli.scorza.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.cannoli.scorza.R
import dev.cannoli.ui.components.PillRowText
import dev.cannoli.ui.components.screenPadding
import dev.cannoli.ui.theme.GrayText
import dev.cannoli.ui.theme.LocalCannoliTypography
import dev.cannoli.ui.theme.ProgressTrack
import dev.cannoli.ui.theme.Radius
import dev.cannoli.ui.theme.Spacing

@Composable
fun InstallingScreen(
    progress: Float,
    statusLabel: String,
    finished: Boolean
) {
    val typo = LocalCannoliTypography.current
    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(durationMillis = 600),
        label = "installProgress"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(screenPadding),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Image(
                painter = painterResource(R.drawable.cannoli_nobg),
                contentDescription = null,
                modifier = Modifier.size(128.dp)
            )

            Spacer(modifier = Modifier.height(Spacing.Xl))

            Text(
                text = statusLabel,
                style = typo.bodyMedium,
                color = GrayText
            )

            if (!finished) {
                Spacer(modifier = Modifier.height(Spacing.Md))

                LinearProgressIndicator(
                    progress = { animatedProgress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 320.dp)
                        .height(6.dp)
                        .clip(RoundedCornerShape(Radius.Sm)),
                    color = Color.White,
                    trackColor = ProgressTrack,
                    gapSize = 0.dp,
                    drawStopIndicator = {}
                )
            } else {
                Spacer(modifier = Modifier.height(Spacing.Lg))

                PillRowText(
                    label = stringResource(R.string.install_continue),
                    isSelected = true,
                    fontSize = typo.bodyLarge.fontSize,
                    lineHeight = typo.bodyLarge.lineHeight,
                    verticalPadding = 4.dp
                )
            }
        }
    }
}
