package dev.cannoli.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cannoli.ui.ButtonStyle
import dev.cannoli.ui.R
import dev.cannoli.ui.theme.CannoliColors
import dev.cannoli.ui.theme.CannoliTypography
import dev.cannoli.ui.theme.LocalCannoliColors
import dev.cannoli.ui.theme.LocalCannoliTypography
import dev.cannoli.ui.theme.Spacing

@Composable
fun RommConnectedOverlay(host: String, username: String?, version: String?, buttonStyle: ButtonStyle = ButtonStyle()) {
    val typo = LocalCannoliTypography.current
    val colors = LocalCannoliColors.current
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = stringResource(R.string.romm_conn_title),
                style = typo.titleLarge.copy(color = colors.text)
            )

            Spacer(modifier = Modifier.height(Spacing.Lg))

            Column(
                modifier = Modifier
                    .fillMaxWidth(0.82f)
                    .background(colors.text.copy(alpha = 0.05f), shape)
                    .border(1.dp, colors.text.copy(alpha = 0.20f), shape)
            ) {
                ConnRow(stringResource(R.string.setting_romm_host), host.substringAfter("://", host), typo, colors)
                if (username != null) {
                    ConnDivider(colors)
                    ConnRow(stringResource(R.string.romm_conn_account), username, typo, colors)
                }
                if (version != null) {
                    ConnDivider(colors)
                    ConnRow(stringResource(R.string.romm_conn_server), version, typo, colors)
                }
            }
        }

        BottomBar(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(screenPadding),
            leftItems = listOf(buttonStyle.back to stringResource(R.string.label_back)),
            rightItems = listOf(buttonStyle.north to stringResource(R.string.label_logout))
        )
    }
}

@Composable
private fun ConnRow(label: String, value: String, typo: CannoliTypography, colors: CannoliColors) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.Md, vertical = Spacing.Sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label.uppercase(),
            style = typo.labelSmall.copy(color = colors.text.copy(alpha = 0.55f), letterSpacing = 1.sp)
        )
        Spacer(modifier = Modifier.width(Spacing.Md))
        Text(
            text = value,
            style = typo.bodyMedium.copy(color = colors.text),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun ConnDivider(colors: CannoliColors) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(colors.text.copy(alpha = 0.12f))
    )
}
