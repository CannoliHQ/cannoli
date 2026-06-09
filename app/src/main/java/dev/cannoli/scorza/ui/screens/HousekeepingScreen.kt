package dev.cannoli.scorza.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.annotation.StringRes
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.cannoli.scorza.R
import dev.cannoli.scorza.ui.effectivePortraitMarginDp
import dev.cannoli.ui.components.CannoliProgressBar
import dev.cannoli.ui.components.screenPadding
import dev.cannoli.ui.theme.GrayText
import dev.cannoli.ui.theme.LocalCannoliTypography
import dev.cannoli.ui.theme.Spacing

enum class HousekeepingKind(@StringRes val titleRes: Int, @StringRes val subtitleRes: Int? = null) {
    DATABASE_MIGRATION(R.string.housekeeping_db_title, R.string.housekeeping_db_subtitle),
    INITIAL_SCAN(R.string.housekeeping_initial_title),
    LIBRARY_REFRESH(R.string.housekeeping_refresh_title),
}

@Composable
fun HousekeepingScreen(
    kind: HousekeepingKind,
    progress: Float,
    statusLabel: String,
) {
    val typo = LocalCannoliTypography.current

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(bottom = effectivePortraitMarginDp())
            .padding(screenPadding),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Image(
                painter = painterResource(R.drawable.logo),
                contentDescription = null,
                modifier = Modifier.size(128.dp)
            )

            Spacer(modifier = Modifier.height(Spacing.Xl))

            Text(
                text = stringResource(kind.titleRes),
                style = typo.bodyLarge,
                color = GrayText
            )

            if (kind.subtitleRes != null) {
                Spacer(modifier = Modifier.height(Spacing.Xs))
                Text(
                    text = stringResource(kind.subtitleRes),
                    style = typo.bodyMedium,
                    color = GrayText
                )
            }

            Spacer(modifier = Modifier.height(Spacing.Sm))

            Text(
                text = statusLabel,
                style = typo.bodyMedium,
                color = GrayText
            )

            Spacer(modifier = Modifier.height(Spacing.Md))

            CannoliProgressBar(
                progress = progress,
                modifier = Modifier.widthIn(max = 320.dp)
            )
        }
    }
}
