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
import dev.cannoli.scorza.romm.RommPlatform
import dev.cannoli.ui.ButtonStyle
import dev.cannoli.ui.R
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
fun RommPlatformListScreen(
    platforms: List<RommPlatform>,
    selectedIndex: Int,
    scrollTarget: Int,
    backgroundImagePath: String?,
    backgroundTint: Int,
    listFontSize: TextUnit = 22.sp,
    listLineHeight: TextUnit = 32.sp,
    listVerticalPadding: Dp = 6.dp,
    onListStateChanged: ((androidx.compose.foundation.lazy.LazyListState?) -> Unit)? = null,
    buttonStyle: ButtonStyle = ButtonStyle(),
) {
    val itemHeight = pillItemHeight(listLineHeight, listVerticalPadding)
    ScreenBackground(backgroundImagePath = backgroundImagePath, backgroundTint = backgroundTint) {
        Box(modifier = Modifier.fillMaxSize().padding(screenPadding)) {
            Column(modifier = Modifier.fillMaxSize().padding(bottom = footerReservation())) {
                ScreenTitle(
                    text = stringResource(R.string.romm_browse_title),
                    fontSize = listFontSize,
                    lineHeight = listLineHeight,
                )
                Spacer(modifier = Modifier.height(Spacing.Sm))
                List(
                    items = platforms,
                    selectedIndex = selectedIndex.coerceIn(0, (platforms.size - 1).coerceAtLeast(0)),
                    itemHeight = itemHeight,
                    scrollTarget = scrollTarget,
                    onListStateChanged = onListStateChanged,
                ) { _, platform, isSelected ->
                    PillRowKeyValue(
                        label = platform.displayName,
                        value = platform.romCount.toString(),
                        isSelected = isSelected,
                        fontSize = listFontSize,
                        lineHeight = listLineHeight,
                        verticalPadding = listVerticalPadding,
                    )
                }
            }
            BottomBar(
                modifier = Modifier.align(Alignment.BottomCenter),
                leftItems = listOf(buttonStyle.back to stringResource(R.string.label_back)),
                rightItems = listOf(buttonStyle.confirm to stringResource(R.string.label_select)),
            )
        }
    }
}
