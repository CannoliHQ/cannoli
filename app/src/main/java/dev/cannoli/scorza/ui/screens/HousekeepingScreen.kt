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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import dev.cannoli.scorza.R
import dev.cannoli.ui.components.CannoliProgressBar
import dev.cannoli.ui.components.screenPadding
import dev.cannoli.ui.theme.GrayText
import dev.cannoli.ui.theme.LocalCannoliTypography
import dev.cannoli.ui.theme.Spacing

const val HOUSEKEEPING_DEFER_MS = 250L

enum class HousekeepingKind(val title: String, val subtitle: String? = null) {
    DATABASE_MIGRATION("Updating your library", "Migrating legacy data"),
    LIBRARY_REFRESH("Refreshing library"),
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
                text = kind.title,
                style = typo.bodyLarge,
                color = GrayText
            )

            if (kind.subtitle != null) {
                Spacer(modifier = Modifier.height(Spacing.Xs))
                Text(
                    text = kind.subtitle,
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
