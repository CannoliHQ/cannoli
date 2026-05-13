package dev.cannoli.scorza.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cannoli.scorza.R
import dev.cannoli.ui.ButtonStyle
import dev.cannoli.ui.components.BottomBar
import dev.cannoli.ui.components.ScreenBackground
import dev.cannoli.ui.components.ScreenTitle
import dev.cannoli.ui.components.footerReservation
import dev.cannoli.ui.components.screenPadding
import dev.cannoli.ui.theme.GrayText
import dev.cannoli.ui.theme.LocalCannoliTypography
import dev.cannoli.ui.theme.Spacing

@Composable
fun BootErrorScreen(
    message: String,
    buttonStyle: ButtonStyle = ButtonStyle(),
) {
    val typo = LocalCannoliTypography.current
    ScreenBackground(backgroundImagePath = null) {
        Box(modifier = Modifier.fillMaxSize().padding(screenPadding)) {
            Column(modifier = Modifier.fillMaxSize().padding(bottom = footerReservation())) {
                ScreenTitle(text = stringResource(R.string.boot_error_title), fontSize = 22.sp, lineHeight = 32.sp)
                Spacer(modifier = Modifier.height(Spacing.Md))
                Text(
                    text = message,
                    style = typo.bodyMedium,
                    color = GrayText,
                    modifier = Modifier.widthIn(max = 560.dp),
                )
            }
            BottomBar(
                modifier = Modifier.align(Alignment.BottomCenter),
                leftItems = emptyList(),
                rightItems = listOf(buttonStyle.confirm to stringResource(R.string.label_retry)),
            )
        }
    }
}
