package dev.cannoli.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.cannoli.ui.theme.Spacing
import dev.cannoli.ui.R
import dev.cannoli.ui.ButtonStyle
import dev.cannoli.ui.components.BottomBar
import dev.cannoli.ui.components.screenPadding
import dev.cannoli.ui.theme.LocalCannoliColors
import dev.cannoli.ui.theme.LocalCannoliTypography

@Composable
fun RAAccountOverlay(username: String, buttonStyle: ButtonStyle = ButtonStyle()) {
    val typo = LocalCannoliTypography.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = stringResource(R.string.ra_title),
                style = typo.titleLarge.copy(color = Color.White)
            )

            Spacer(modifier = Modifier.height(Spacing.Md))

            Text(
                text = stringResource(R.string.ra_logged_in_as),
                style = typo.bodyMedium.copy(color = LocalCannoliColors.current.text.copy(alpha = 0.6f))
            )

            Text(
                text = username,
                style = typo.bodyLarge.copy(color = Color.White)
            )
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
