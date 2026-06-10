package dev.cannoli.scorza.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import dev.cannoli.scorza.romm.RommArtUrl
import dev.cannoli.scorza.romm.RommGame
import dev.cannoli.ui.ButtonStyle
import dev.cannoli.ui.R
import dev.cannoli.ui.components.BottomBar
import dev.cannoli.ui.components.ScreenBackground
import dev.cannoli.ui.components.ScreenTitle
import dev.cannoli.ui.components.footerReservation
import dev.cannoli.ui.components.screenPadding
import dev.cannoli.ui.theme.LocalCannoliColors
import dev.cannoli.ui.theme.LocalCannoliTypography
import dev.cannoli.ui.theme.Radius
import dev.cannoli.ui.theme.Spacing

@Composable
fun RommGameDetailScreen(
    game: RommGame,
    host: String,
    imageLoader: coil.ImageLoader,
    backgroundImagePath: String?,
    backgroundTint: Int,
    listFontSize: TextUnit = 22.sp,
    listLineHeight: TextUnit = 32.sp,
    buttonStyle: ButtonStyle = ButtonStyle(),
) {
    val typo = LocalCannoliTypography.current
    val colors = LocalCannoliColors.current

    ScreenBackground(backgroundImagePath = backgroundImagePath, backgroundTint = backgroundTint) {
        Box(modifier = Modifier.fillMaxSize().padding(screenPadding)) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = footerReservation())
                    .verticalScroll(rememberScrollState())
            ) {
                ScreenTitle(
                    text = game.name,
                    fontSize = listFontSize,
                    lineHeight = listLineHeight,
                )
                Spacer(modifier = Modifier.height(Spacing.Md))

                val coverUrl = RommArtUrl.resolve(host, game.coverPath)
                if (coverUrl != null) {
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        AsyncImage(
                            model = coverUrl,
                            contentDescription = null,
                            imageLoader = imageLoader,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .size(width = 180.dp, height = 240.dp)
                                .clip(RoundedCornerShape(Radius.Lg)),
                        )
                    }
                    Spacer(modifier = Modifier.height(Spacing.Md))
                }

                if (game.regions.isNotEmpty()) {
                    DetailRow(
                        label = stringResource(dev.cannoli.ui.R.string.romm_detail_region),
                        value = game.regions.joinToString(", "),
                        typo = typo,
                        colors = colors,
                    )
                }
                if (!game.revision.isNullOrBlank()) {
                    DetailRow(
                        label = stringResource(dev.cannoli.ui.R.string.romm_detail_revision),
                        value = game.revision,
                        typo = typo,
                        colors = colors,
                    )
                }
                DetailRow(
                    label = stringResource(dev.cannoli.ui.R.string.romm_detail_size),
                    value = formatBytes(game.sizeBytes),
                    typo = typo,
                    colors = colors,
                )
                if (game.languages.isNotEmpty()) {
                    DetailRow(
                        label = stringResource(dev.cannoli.ui.R.string.romm_detail_languages),
                        value = game.languages.joinToString(", "),
                        typo = typo,
                        colors = colors,
                    )
                }

                if (!game.summary.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(Spacing.Sm))
                    Text(
                        text = game.summary,
                        style = typo.bodyMedium.copy(color = colors.text.copy(alpha = 0.8f)),
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }

                if (game.files.size > 1) {
                    Spacer(modifier = Modifier.height(Spacing.Md))
                    Text(
                        text = stringResource(dev.cannoli.ui.R.string.romm_detail_files),
                        style = typo.labelSmall.copy(
                            color = colors.text.copy(alpha = 0.55f),
                            letterSpacing = 1.sp,
                        ),
                        modifier = Modifier.padding(bottom = Spacing.Xs),
                    )
                    game.files.forEach { file ->
                        Text(
                            text = "${file.fileName}  ${formatBytes(file.sizeBytes)}",
                            style = typo.bodyMedium.copy(color = colors.text),
                            modifier = Modifier.padding(vertical = 2.dp),
                        )
                    }
                }
            }

            BottomBar(
                modifier = Modifier.align(Alignment.BottomCenter),
                leftItems = listOf(buttonStyle.back to stringResource(R.string.label_back)),
                rightItems = emptyList(),
            )
        }
    }
}

@Composable
private fun DetailRow(
    label: String,
    value: String,
    typo: dev.cannoli.ui.theme.CannoliTypography,
    colors: dev.cannoli.ui.theme.CannoliColors,
) {
    Column(modifier = Modifier.padding(vertical = 2.dp)) {
        Text(
            text = label.uppercase(),
            style = typo.labelSmall.copy(
                color = colors.text.copy(alpha = 0.55f),
                letterSpacing = 1.sp,
            ),
        )
        Text(
            text = value,
            style = typo.bodyMedium.copy(color = colors.text),
        )
    }
}

private fun formatBytes(bytes: Long): String {
    return when {
        bytes >= 1_073_741_824L -> "%.1f GB".format(bytes / 1_073_741_824.0)
        bytes >= 1_048_576L -> "%.1f MB".format(bytes / 1_048_576.0)
        bytes >= 1_024L -> "%.1f KB".format(bytes / 1_024.0)
        else -> "$bytes B"
    }
}
