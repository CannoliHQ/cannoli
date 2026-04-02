package dev.cannoli.scorza.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cannoli.scorza.BuildConfig
import dev.cannoli.scorza.R
import dev.cannoli.scorza.settings.ButtonLabelSet
import dev.cannoli.scorza.ui.theme.LocalCannoliFont

@Composable
fun AboutOverlay(statusMessage: String? = null, updateAvailable: Boolean = false, buttonLabelSet: ButtonLabelSet = ButtonLabelSet.PLUMBER) {
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
            Image(
                painter = painterResource(R.drawable.cannoli_nobg),
                contentDescription = null,
                modifier = Modifier.size(128.dp),
            )

            Text(
                text = stringResource(R.string.about_title),
                style = TextStyle(
                    fontFamily = LocalCannoliFont.current,
                    fontWeight = FontWeight.Bold,
                    fontSize = 32.sp,
                    color = Color.White
                )
            )

            Text(
                text = stringResource(R.string.about_description),
                style = TextStyle(
                    fontFamily = LocalCannoliFont.current,
                    fontSize = 16.sp,
                    color = Color.White,
                    textAlign = TextAlign.Center
                )
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "v${BuildConfig.VERSION_NAME}  •  ${BuildConfig.BUILD_DATE}  •  ${BuildConfig.GIT_HASH}",
                style = TextStyle(
                    fontFamily = LocalCannoliFont.current,
                    fontSize = 18.sp,
                    color = Color.White
                )
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = stringResource(R.string.about_website),
                style = TextStyle(
                    fontFamily = LocalCannoliFont.current,
                    fontSize = 18.sp,
                    color = Color.White
                )
            )

            if (statusMessage != null) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = statusMessage,
                    style = TextStyle(
                        fontFamily = LocalCannoliFont.current,
                        fontSize = 14.sp,
                        color = Color(0xFF90EE90)
                    )
                )
            }
        }
        BottomBar(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(screenPadding),
            leftItems = listOf(buttonLabelSet.back to stringResource(R.string.label_back)),
            rightItems = buildList {
                if (updateAvailable) add(buttonLabelSet.y to stringResource(R.string.label_update))
                add(buttonLabelSet.x to stringResource(R.string.label_credits))
            }
        )
    }
}
