package dev.cannoli.scorza.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.cannoli.scorza.R
import dev.cannoli.ui.ButtonStyle
import dev.cannoli.ui.DPAD_HORIZONTAL
import dev.cannoli.ui.START_GLYPH
import dev.cannoli.ui.components.BottomBar
import dev.cannoli.ui.components.PillRowKeyValue
import dev.cannoli.ui.components.screenPadding
import dev.cannoli.ui.theme.GrayText
import dev.cannoli.ui.theme.LocalCannoliTypography
import dev.cannoli.ui.theme.Spacing

@Composable
fun SetupScreen(
    storageLabel: String,
    selectedIndex: Int,
    isCustom: Boolean = false,
    customPath: String? = null,
    continueEnabled: Boolean = true,
    buttonStyle: ButtonStyle = ButtonStyle()
) {
    val typo = LocalCannoliTypography.current
    val fontSize = typo.bodyLarge.fontSize
    val lineHeight = typo.bodyLarge.lineHeight
    val verticalPadding = 4.dp

    val folderIndex = if (isCustom) 1 else -1

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(screenPadding)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Image(
                    painter = painterResource(R.drawable.cannoli_nobg),
                    contentDescription = null,
                    modifier = Modifier.size(64.dp)
                )

                Spacer(modifier = Modifier.height(Spacing.Md))

                Text(
                    text = stringResource(R.string.setup_welcome),
                    style = typo.titleLarge,
                    color = Color.White
                )

                Spacer(modifier = Modifier.height(Spacing.Sm))

                Text(
                    text = stringResource(R.string.setup_description),
                    style = typo.bodyMedium,
                    color = GrayText
                )
            }

            Spacer(modifier = Modifier.height(Spacing.Xl))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
            ) {
                PillRowKeyValue(
                    label = stringResource(R.string.setup_storage),
                    value = storageLabel,
                    isSelected = selectedIndex == 0,
                    fontSize = fontSize,
                    lineHeight = lineHeight,
                    verticalPadding = verticalPadding
                )

                if (isCustom) {
                    Spacer(modifier = Modifier.height(Spacing.Md))

                    PillRowKeyValue(
                        label = stringResource(R.string.setup_folder_label),
                        value = customPath ?: stringResource(R.string.setup_folder_unset),
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
            0 -> rightItems.add(DPAD_HORIZONTAL to stringResource(R.string.label_change))
            folderIndex -> rightItems.add(buttonStyle.confirm to stringResource(R.string.label_select))
        }
        if (continueEnabled) {
            rightItems.add(START_GLYPH to stringResource(R.string.label_continue))
        }

        BottomBar(
            modifier = Modifier.align(Alignment.BottomCenter),
            leftItems = listOf(buttonStyle.back to stringResource(R.string.label_quit)),
            rightItems = rightItems
        )
    }
}
