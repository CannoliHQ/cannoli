package dev.cannoli.scorza.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.cannoli.scorza.R
import dev.cannoli.igm.ButtonLabelSet
import dev.cannoli.igm.ui.components.BottomBar
import dev.cannoli.igm.ui.components.screenPadding
import dev.cannoli.igm.ui.theme.LocalCannoliFont

@Composable
fun RALoggingInOverlay(message: String, buttonLabelSet: ButtonLabelSet = ButtonLabelSet.PLUMBER) {
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
                style = TextStyle(
                    fontFamily = LocalCannoliFont.current,
                    fontWeight = FontWeight.Bold,
                    fontSize = 28.sp,
                    color = Color.White
                )
            )
            Text(
                text = message,
                style = TextStyle(
                    fontFamily = LocalCannoliFont.current,
                    fontSize = 18.sp,
                    color = Color.White.copy(alpha = 0.7f)
                )
            )
        }
        BottomBar(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(screenPadding),
            leftItems = listOf(buttonLabelSet.back to stringResource(R.string.label_back)),
            rightItems = emptyList()
        )
    }
}
