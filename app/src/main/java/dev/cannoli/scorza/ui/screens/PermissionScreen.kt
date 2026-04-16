package dev.cannoli.scorza.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.cannoli.igm.ui.theme.Spacing
import androidx.compose.ui.unit.sp
import dev.cannoli.scorza.R
import dev.cannoli.igm.ui.theme.GrayText

@Composable
fun PermissionScreen() {
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
            style = MaterialTheme.typography.bodyLarge.copy(
                fontSize = 28.sp,
                lineHeight = 36.sp
            ),
            color = Color.White
        )

        Spacer(modifier = Modifier.height(Spacing.Sm))

        Text(
            text = stringResource(R.string.permission_description),
            style = MaterialTheme.typography.bodyMedium.copy(
                fontSize = 16.sp
            ),
            color = GrayText
        )

        Spacer(modifier = Modifier.height(Spacing.Lg))

        Text(
            text = stringResource(R.string.permission_grant),
            style = MaterialTheme.typography.bodyMedium.copy(
                fontSize = 16.sp
            ),
            color = GrayText
        )
    }
}
