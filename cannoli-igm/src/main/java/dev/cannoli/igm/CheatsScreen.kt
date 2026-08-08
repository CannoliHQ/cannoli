package dev.cannoli.igm

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import dev.cannoli.ui.components.BottomBar
import dev.cannoli.ui.components.List
import dev.cannoli.ui.components.PillRowKeyValue
import dev.cannoli.ui.components.ScreenBackground
import dev.cannoli.ui.components.ScreenTitle
import dev.cannoli.ui.components.footerReservation
import dev.cannoli.ui.components.pillItemHeight
import dev.cannoli.ui.components.pillVerticalPadding
import dev.cannoli.ui.components.screenInsets
import dev.cannoli.ui.theme.Spacing

sealed interface CheatListItem {
    data class FileSelector(val label: String, val value: String) : CheatListItem
    data class Cheat(val label: String, val value: String, val supported: Boolean) : CheatListItem
}

@Composable
fun CheatsScreen(
    title: String,
    items: kotlin.collections.List<CheatListItem>,
    selectedIndex: Int,
    bottomBarLeft: kotlin.collections.List<Pair<String, String>>,
    bottomBarRight: kotlin.collections.List<Pair<String, String>>,
    fontSize: TextUnit = 22.sp,
    lineHeight: TextUnit = 32.sp,
) {
    val verticalPadding = pillVerticalPadding()
    val itemHeight = pillItemHeight(lineHeight, verticalPadding)

    ScreenBackground(backgroundImagePath = null, backgroundAlpha = 0.85f, backgroundColor = Color.Black) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(screenInsets())
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = footerReservation())
            ) {
                ScreenTitle(text = title, fontSize = fontSize, lineHeight = lineHeight)
                Spacer(modifier = Modifier.height(Spacing.Sm))
                List(
                    items = items,
                    selectedIndex = selectedIndex,
                    itemHeight = itemHeight
                ) { _, item, isSelected ->
                    when (item) {
                        is CheatListItem.FileSelector -> PillRowKeyValue(
                            label = item.label,
                            value = item.value,
                            isSelected = isSelected,
                            fontSize = fontSize,
                            lineHeight = lineHeight,
                            verticalPadding = verticalPadding
                        )
                        is CheatListItem.Cheat -> Box(
                            modifier = if (item.supported) Modifier else Modifier.alpha(0.4f)
                        ) {
                            PillRowKeyValue(
                                label = item.label,
                                value = item.value,
                                isSelected = isSelected,
                                fontSize = fontSize,
                                lineHeight = lineHeight,
                                verticalPadding = verticalPadding
                            )
                        }
                    }
                }
            }

            BottomBar(
                modifier = Modifier.align(Alignment.BottomCenter),
                leftItems = bottomBarLeft,
                rightItems = bottomBarRight
            )
        }
    }
}
