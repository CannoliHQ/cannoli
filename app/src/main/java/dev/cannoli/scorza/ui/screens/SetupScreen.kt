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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cannoli.scorza.R
import dev.cannoli.scorza.ui.components.BottomBar
import dev.cannoli.scorza.ui.components.PillRowKeyValue
import dev.cannoli.scorza.ui.components.screenPadding
import dev.cannoli.scorza.ui.theme.GrayText

@Composable
fun SetupScreen(
    storageLabel: String,
    selectedIndex: Int,
    isCustom: Boolean = false,
    customPath: String? = null,
    continueEnabled: Boolean = true
) {
    val fontSize = 22.sp
    val lineHeight = 32.sp
    val verticalPadding = 4.dp

    val folderIndex = if (isCustom) 1 else -1

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = screenPadding, vertical = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Image(
                painter = painterResource(R.drawable.cannoli_nobg),
                contentDescription = null,
                modifier = Modifier.size(128.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = stringResource(R.string.setup_welcome),
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontSize = 28.sp,
                    lineHeight = 36.sp
                ),
                color = Color.White
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = stringResource(R.string.setup_description),
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = 16.sp
                ),
                color = GrayText
            )

            Spacer(modifier = Modifier.height(32.dp))

            Column(modifier = Modifier.fillMaxSize()) {
                PillRowKeyValue(
                    label = stringResource(R.string.setup_storage),
                    value = storageLabel,
                    isSelected = selectedIndex == 0,
                    fontSize = fontSize,
                    lineHeight = lineHeight,
                    verticalPadding = verticalPadding
                )

                if (isCustom) {
                    Spacer(modifier = Modifier.height(16.dp))

                    PillRowKeyValue(
                        label = "Folder",
                        value = customPath ?: "Not selected",
                        isSelected = selectedIndex == folderIndex,
                        fontSize = fontSize,
                        lineHeight = lineHeight,
                        verticalPadding = verticalPadding
                    )
                }
            }
        }

        val rightItems = mutableListOf<Pair<String, String>>()
        when (selectedIndex) {
            0 -> rightItems.add("←→" to stringResource(R.string.label_change))
            folderIndex -> rightItems.add("A" to stringResource(R.string.label_select))
        }
        if (continueEnabled) {
            rightItems.add("Start" to "Confirm")
        }

        BottomBar(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = screenPadding, vertical = 16.dp),
            leftItems = listOf("B" to stringResource(R.string.label_quit)),
            rightItems = rightItems
        )
    }
}
