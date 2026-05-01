package dev.cannoli.scorza.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cannoli.scorza.R
import dev.cannoli.scorza.navigation.LauncherScreen
import dev.cannoli.scorza.util.LoggingPrefs
import dev.cannoli.ui.ButtonStyle
import dev.cannoli.ui.DPAD_HORIZONTAL
import dev.cannoli.ui.components.BottomBar
import dev.cannoli.ui.components.List
import dev.cannoli.ui.components.PillRowKeyValue
import dev.cannoli.ui.components.ScreenBackground
import dev.cannoli.ui.components.ScreenTitle
import dev.cannoli.ui.components.footerReservation
import dev.cannoli.ui.components.pillItemHeight
import dev.cannoli.ui.components.screenPadding
import dev.cannoli.ui.theme.Spacing

@Composable
fun LoggingSettingsScreen(
    screen: LauncherScreen.LoggingSettings,
    modifier: Modifier = Modifier,
    backgroundImagePath: String? = null,
    backgroundTint: Int = 0,
    listFontSize: TextUnit = 22.sp,
    listLineHeight: TextUnit = 32.sp,
    listVerticalPadding: Dp = 8.dp,
    buttonStyle: ButtonStyle = ButtonStyle(),
) {
    val itemHeight = pillItemHeight(listLineHeight, listVerticalPadding)
    val categories = LoggingPrefs.Category.entries.toList()

    ScreenBackground(backgroundImagePath = backgroundImagePath, backgroundTint = backgroundTint) {
        Box(modifier = modifier.fillMaxSize().padding(screenPadding)) {
            Column(modifier = Modifier.fillMaxSize().padding(bottom = footerReservation())) {
                ScreenTitle(
                    text = stringResource(R.string.logging_title),
                    fontSize = listFontSize,
                    lineHeight = listLineHeight,
                )
                Spacer(modifier = Modifier.height(Spacing.Sm))
                List(
                    items = categories,
                    selectedIndex = screen.selectedIndex.coerceIn(0, categories.size - 1),
                    itemHeight = itemHeight,
                    scrollTarget = screen.scrollTarget,
                ) { _, category, isSelected ->
                    PillRowKeyValue(
                        label = labelFor(category),
                        value = if (LoggingPrefs.isEnabled(category)) stringResource(R.string.value_on)
                            else stringResource(R.string.value_off),
                        isSelected = isSelected,
                        fontSize = listFontSize,
                        lineHeight = listLineHeight,
                        verticalPadding = listVerticalPadding,
                    )
                }
            }
            BottomBar(
                modifier = Modifier.align(Alignment.BottomCenter),
                leftItems = listOf(
                    buttonStyle.back to stringResource(R.string.label_back),
                    DPAD_HORIZONTAL to stringResource(R.string.label_toggle),
                ),
                rightItems = emptyList(),
            )
        }
    }
}

@Composable
private fun labelFor(category: LoggingPrefs.Category): String = when (category) {
    LoggingPrefs.Category.FILE_SCANNER -> stringResource(R.string.logging_file_scanner)
    LoggingPrefs.Category.ROM_SCAN -> stringResource(R.string.logging_rom_scan)
    LoggingPrefs.Category.INPUT -> stringResource(R.string.logging_input)
    LoggingPrefs.Category.SESSION -> stringResource(R.string.logging_session)
}
