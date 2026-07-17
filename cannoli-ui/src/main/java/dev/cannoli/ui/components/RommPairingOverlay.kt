package dev.cannoli.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.cannoli.ui.ButtonStyle
import dev.cannoli.ui.R
import dev.cannoli.ui.theme.LocalCannoliColors
import dev.cannoli.ui.theme.LocalCannoliTypography
import dev.cannoli.ui.theme.Radius
import dev.cannoli.ui.theme.Spacing
import dev.cannoli.ui.theme.SurfaceDim

@Composable
fun RommPairingOverlay(
    host: String,
    message: String,
    userCode: String = "",
    verificationUrl: String = "",
    qrBitmap: Bitmap? = null,
    buttonStyle: ButtonStyle = ButtonStyle(),
) {
    val typo = LocalCannoliTypography.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        if (userCode.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.Xl),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Spacing.Xs)
            ) {
                Text(
                    text = if (message.isEmpty()) stringResource(R.string.romm_connecting) else message,
                    style = typo.titleLarge.copy(color = Color.White),
                    textAlign = TextAlign.Center
                )
                Text(
                    text = host.substringAfter("://", host),
                    style = typo.bodyMedium.copy(color = LocalCannoliColors.current.text.copy(alpha = 0.6f)),
                    textAlign = TextAlign.Center
                )
            }
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = stringResource(R.string.romm_pair_waiting),
                    style = typo.titleLarge.copy(color = Color.White),
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(Spacing.Lg))
                if (qrBitmap != null) {
                    Image(
                        bitmap = qrBitmap.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier
                            .size(200.dp)
                            .clip(RoundedCornerShape(Radius.Lg))
                    )
                    Spacer(modifier = Modifier.height(Spacing.Lg))
                }
                Text(
                    text = verificationUrl.substringAfter("://", verificationUrl).substringBefore('?'),
                    style = typo.bodyMedium.copy(color = Color.White, textAlign = TextAlign.Center)
                )
                Spacer(modifier = Modifier.height(Spacing.Lg))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (char in userCode) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(Radius.Lg))
                                .background(SurfaceDim),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = char.toString(),
                                style = typo.bodyLarge.copy(color = Color.White)
                            )
                        }
                    }
                }
            }
        }
        BottomBar(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(screenPadding),
            leftItems = listOf(buttonStyle.back to stringResource(R.string.label_back)),
            rightItems = emptyList()
        )
    }
}
