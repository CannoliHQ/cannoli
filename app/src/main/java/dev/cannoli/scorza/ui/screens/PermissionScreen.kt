package dev.cannoli.scorza.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.cannoli.scorza.R
import dev.cannoli.ui.theme.GrayText
import dev.cannoli.ui.theme.LocalCannoliTypography
import dev.cannoli.ui.theme.Spacing

@Composable
fun PermissionScreen() {
    val typo = LocalCannoliTypography.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Image(
            painter = painterResource(R.drawable.cannoli_nobg),
            contentDescription = null,
            modifier = Modifier.size(128.dp)
        )

        Spacer(modifier = Modifier.height(Spacing.Md))

        Text(
            text = stringResource(R.string.permission_title),
            style = typo.titleLarge,
            color = Color.White
        )

        Spacer(modifier = Modifier.height(Spacing.Sm))

        Text(
            text = stringResource(R.string.permission_description),
            style = typo.bodyMedium,
            color = GrayText
        )

        Spacer(modifier = Modifier.height(Spacing.Lg))

        Text(
            text = stringResource(R.string.permission_grant),
            style = typo.bodyMedium,
            color = GrayText
        )
    }
}
