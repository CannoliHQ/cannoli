package dev.cannoli.scorza.ui.screens

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cannoli.scorza.navigation.LauncherScreen
import dev.cannoli.ui.ButtonStyle
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
fun RetroAchievementsOfflineSetsScreen(
    screen: LauncherScreen.RetroAchievementsOfflineSets,
    modifier: Modifier = Modifier,
    backgroundImagePath: String? = null,
    backgroundTint: Int = 0,
    listFontSize: TextUnit = 22.sp,
    listLineHeight: TextUnit = 32.sp,
    listVerticalPadding: Dp = 6.dp,
    buttonStyle: ButtonStyle = ButtonStyle(),
    onListStateChanged: ((LazyListState?) -> Unit)? = null,
) {
    val entries = screen.entries
    ScreenBackground(backgroundImagePath = backgroundImagePath, backgroundTint = backgroundTint) {
        Box(modifier = modifier.fillMaxSize().padding(screenPadding)) {
            Column(modifier = Modifier.fillMaxSize().padding(bottom = footerReservation())) {
                ScreenTitle(
                    text = screen.platformName.ifEmpty { stringResource(dev.cannoli.ui.R.string.ra_offline_sets_title) },
                    fontSize = listFontSize,
                    lineHeight = listLineHeight,
                )
                Spacer(modifier = Modifier.height(Spacing.Sm))
                List(
                    items = entries,
                    selectedIndex = screen.selectedIndex.coerceIn(0, (entries.size - 1).coerceAtLeast(0)),
                    itemHeight = pillItemHeight(listLineHeight, listVerticalPadding),
                    scrollTarget = screen.scrollTarget,
                    onListStateChanged = onListStateChanged,
                ) { _, entry, isSelected ->
                    val rel = DateUtils.getRelativeTimeSpanString(entry.cachedAtMs).toString()
                        .replaceFirstChar { it.uppercaseChar() }
                    PillRowKeyValue(
                        label = entry.gameName,
                        value = stringResource(dev.cannoli.ui.R.string.ra_offline_set_value, entry.achievementCount, rel),
                        isSelected = isSelected,
                        fontSize = listFontSize,
                        lineHeight = listLineHeight,
                        verticalPadding = listVerticalPadding,
                    )
                }
            }
            BottomBar(
                modifier = Modifier.align(Alignment.BottomCenter),
                leftItems = listOf(buttonStyle.back to stringResource(dev.cannoli.ui.R.string.label_back)),
                rightItems = if (entries.isEmpty()) emptyList() else listOf(
                    buttonStyle.north to stringResource(dev.cannoli.ui.R.string.label_refresh),
                    buttonStyle.west to stringResource(dev.cannoli.ui.R.string.label_delete),
                ),
            )
        }
    }
}
