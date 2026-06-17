package dev.cannoli.scorza.ui.screens

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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cannoli.scorza.ui.viewmodel.RommBrowseViewModel
import dev.cannoli.ui.ButtonStyle
import dev.cannoli.ui.R
import dev.cannoli.ui.START_GLYPH
import dev.cannoli.ui.components.BottomBar
import dev.cannoli.ui.components.List
import dev.cannoli.ui.components.PillRowKeyValue
import dev.cannoli.ui.components.ScreenBackground
import dev.cannoli.ui.components.ScreenTitle
import dev.cannoli.ui.components.footerReservation
import dev.cannoli.ui.components.pillItemHeight
import dev.cannoli.ui.components.screenPadding
import dev.cannoli.ui.theme.LocalCannoliColors
import dev.cannoli.ui.theme.LocalCannoliFont
import dev.cannoli.ui.theme.Spacing

@Composable
fun RommFirmwareListScreen(
    title: String,
    rows: List<RommBrowseViewModel.RommFirmwareRow>,
    checkedIds: Set<Int>,
    loading: Boolean,
    error: Boolean,
    selectedIndex: Int,
    scrollTarget: Int,
    backgroundImagePath: String?,
    backgroundTint: Int,
    listFontSize: TextUnit = 22.sp,
    listLineHeight: TextUnit = 32.sp,
    listVerticalPadding: Dp = 6.dp,
    buttonStyle: ButtonStyle = ButtonStyle(),
) {
    val colors = LocalCannoliColors.current
    val font = LocalCannoliFont.current
    val muted = colors.text.copy(alpha = 0.45f)

    ScreenBackground(backgroundImagePath = backgroundImagePath, backgroundTint = backgroundTint) {
        Box(modifier = Modifier.fillMaxSize().padding(screenPadding)) {
            Column(modifier = Modifier.fillMaxSize().padding(bottom = footerReservation())) {
                ScreenTitle(
                    text = title,
                    fontSize = listFontSize,
                    lineHeight = listLineHeight,
                )
                Spacer(modifier = Modifier.height(Spacing.Sm))
                when {
                    loading -> Text(
                        text = stringResource(R.string.boot_preparing),
                        color = muted,
                        fontFamily = font,
                        fontSize = listFontSize,
                    )
                    error -> Text(
                        text = stringResource(R.string.romm_firmware_error),
                        color = muted,
                        fontFamily = font,
                        fontSize = listFontSize,
                    )
                    rows.isEmpty() -> Text(
                        text = stringResource(R.string.romm_firmware_empty),
                        color = muted,
                        fontFamily = font,
                        fontSize = listFontSize,
                    )
                    else -> List(
                        items = rows,
                        selectedIndex = selectedIndex.coerceIn(0, (rows.size - 1).coerceAtLeast(0)),
                        itemHeight = pillItemHeight(listLineHeight, listVerticalPadding),
                        scrollTarget = scrollTarget,
                    ) { _, row, isSelected ->
                        PillRowKeyValue(
                            label = row.firmware.fileName,
                            value = if (row.present) stringResource(R.string.romm_firmware_present) else "",
                            isSelected = isSelected,
                            fontSize = listFontSize,
                            lineHeight = listLineHeight,
                            verticalPadding = listVerticalPadding,
                            checkState = row.firmware.id in checkedIds,
                        )
                    }
                }
            }
            BottomBar(
                modifier = Modifier.align(Alignment.BottomCenter),
                leftItems = listOf(buttonStyle.back to stringResource(R.string.label_back)),
                rightItems = if (rows.isEmpty()) emptyList() else listOf(
                    buttonStyle.confirm to stringResource(R.string.label_toggle),
                    START_GLYPH to stringResource(R.string.label_download),
                ),
            )
        }
    }
}
