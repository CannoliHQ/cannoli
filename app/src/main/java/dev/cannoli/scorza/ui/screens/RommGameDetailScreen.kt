package dev.cannoli.scorza.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import dev.cannoli.scorza.romm.LocalState
import dev.cannoli.scorza.romm.RommArtType
import dev.cannoli.scorza.romm.RommArtUrl
import dev.cannoli.scorza.romm.RommGame
import dev.cannoli.ui.ButtonStyle
import dev.cannoli.ui.R
import dev.cannoli.ui.components.BottomBar
import dev.cannoli.ui.components.ScreenBackground
import dev.cannoli.ui.components.ScreenTitle
import dev.cannoli.ui.components.footerReservation
import dev.cannoli.ui.components.pillInternalH
import dev.cannoli.ui.components.screenPadding
import dev.cannoli.ui.theme.LocalCannoliColors
import dev.cannoli.ui.theme.LocalCannoliFont
import dev.cannoli.ui.theme.Spacing
import kotlin.math.ceil

private const val DOWNLOAD_ENABLED = true
private val SCROLL_STEP = 56.dp

@Composable
fun RommGameDetailScreen(
    game: RommGame,
    platformName: String,
    localState: LocalState,
    host: String,
    artType: RommArtType = RommArtType.NONE,
    imageLoader: coil.ImageLoader,
    scrollStep: Int,
    onScrollStepChanged: (Int) -> Unit,
    listFontSize: TextUnit = 22.sp,
    listLineHeight: TextUnit = 32.sp,
    buttonStyle: ButtonStyle = ButtonStyle(),
) {
    ScreenBackground(backgroundImagePath = null) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize().padding(screenPadding)) {
            val scale = RommGameDetailLayout.scaleFor(maxWidth.value, maxHeight.value)
            val coverW = maxWidth * 0.32f

            val scrollState = rememberScrollState()
            val stepPx = with(LocalDensity.current) { SCROLL_STEP.toPx() }
            LaunchedEffect(scrollStep, scrollState.maxValue, stepPx) {
                val maxValue = scrollState.maxValue
                val maxStep = if (stepPx > 0f) ceil(maxValue / stepPx).toInt() else 0
                val clamped = scrollStep.coerceIn(0, maxStep)
                if (clamped != scrollStep) onScrollStepChanged(clamped)
                scrollState.scrollTo((clamped * stepPx).toInt().coerceAtMost(maxValue))
            }

            Column(modifier = Modifier.fillMaxSize().padding(bottom = footerReservation())) {
                ScreenTitle(text = game.name, fontSize = listFontSize, lineHeight = listLineHeight)
                Spacer(modifier = Modifier.height(Spacing.Xs))
                Subtitle(platformName, localState, scale)
                Spacer(modifier = Modifier.height((12 * scale).dp))
                Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(LocalCannoliColors.current.text.copy(alpha = 0.10f)))
                Spacer(modifier = Modifier.height((12 * scale).dp))

                Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .padding(start = pillInternalH)
                            .verticalScroll(scrollState),
                    ) {
                        DetailBody(game, scale)
                    }
                    val coverUrl = RommArtUrl.forType(host, game, artType)
                    if (coverUrl != null) {
                        Spacer(modifier = Modifier.width((20 * scale).dp))
                        GameCover(coverUrl, imageLoader, Modifier.width(coverW).fillMaxHeight())
                    }
                }
            }

            BottomBar(
                modifier = Modifier.align(Alignment.BottomCenter),
                leftItems = listOf(buttonStyle.back to stringResource(R.string.label_back)),
                rightItems = buildList {
                    if (game.ssMedia?.manual != null)
                        add(buttonStyle.west to stringResource(R.string.label_manual))
                    if (RommGameDetailLayout.showDownloadAction(localState, DOWNLOAD_ENABLED))
                        add(buttonStyle.north to stringResource(R.string.romm_detail_download))
                },
            )
        }
    }
}

@Composable
private fun Subtitle(platformName: String, localState: LocalState, scale: Float) {
    val colors = LocalCannoliColors.current
    val font = LocalCannoliFont.current
    val muted = colors.text.copy(alpha = 0.45f)
    val onDevice = localState == LocalState.PRESENT
    Row(modifier = Modifier.padding(start = pillInternalH), verticalAlignment = Alignment.CenterVertically) {
        Text(text = platformName, color = colors.text.copy(alpha = 0.6f), fontFamily = font, fontSize = (13 * scale).sp)
        Spacer(modifier = Modifier.width((12 * scale).dp))
        Box(
            modifier = Modifier
                .width((7 * scale).dp)
                .height((7 * scale).dp)
                .border(if (onDevice) 0.dp else (1 * scale).dp, if (onDevice) colors.text else muted, CircleShape)
                .background(if (onDevice) colors.text else Color.Transparent, CircleShape),
        )
        Spacer(modifier = Modifier.width((7 * scale).dp))
        Text(
            text = stringResource(if (onDevice) R.string.romm_detail_on_device else R.string.romm_detail_not_on_device),
            color = if (onDevice) colors.text.copy(alpha = 0.8f) else muted,
            fontFamily = font, fontSize = (10.5f * scale).sp, letterSpacing = 1.5.sp,
        )
    }
}

@Composable
private fun GameCover(coverUrl: String, imageLoader: coil.ImageLoader, modifier: Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        AsyncImage(
            model = coverUrl,
            contentDescription = null,
            imageLoader = imageLoader,
            contentScale = ContentScale.Fit,
            alignment = Alignment.TopCenter,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun DetailBody(game: RommGame, scale: Float) {
    val colors = LocalCannoliColors.current
    val font = LocalCannoliFont.current
    val muted = colors.text.copy(alpha = 0.45f)
    val secondary = colors.text.copy(alpha = 0.6f)

    if (!game.summary.isNullOrBlank()) {
        Text(text = game.summary, color = secondary, fontFamily = font, fontSize = (12.5f * scale).sp, lineHeight = (20 * scale).sp)
        Spacer(modifier = Modifier.height((16 * scale).dp))
    }

    RommGameDetailLayout.metadataRows(game).forEach { row ->
        Row(modifier = Modifier.padding(vertical = (3 * scale).dp), verticalAlignment = Alignment.Top) {
            Text(text = stringResource(row.labelRes).uppercase(), color = muted, fontFamily = font, fontSize = (10 * scale).sp, lineHeight = (18 * scale).sp, letterSpacing = 1.5.sp, modifier = Modifier.width((104 * scale).dp))
            Text(text = row.value, color = colors.text, fontFamily = font, fontSize = (13 * scale).sp, lineHeight = (18 * scale).sp)
        }
    }
    Spacer(modifier = Modifier.height((8 * scale).dp))
}
