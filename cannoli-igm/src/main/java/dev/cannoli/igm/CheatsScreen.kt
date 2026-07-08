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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cannoli.ui.components.BottomBar
import dev.cannoli.ui.components.ListSection
import dev.cannoli.ui.components.PillRowKeyValue
import dev.cannoli.ui.components.ScreenBackground
import dev.cannoli.ui.components.ScreenTitle
import dev.cannoli.ui.components.SectionedList
import dev.cannoli.ui.components.footerReservation
import dev.cannoli.ui.components.pillItemHeight
import dev.cannoli.ui.components.screenPadding
import dev.cannoli.ui.theme.Spacing

data class CheatRowUi(val label: String, val enabled: Boolean, val supported: Boolean)

private val verticalPadding = 6.dp

@Composable
fun CheatsScreen(
    title: String,
    sections: List<ListSection<CheatRowUi>>,
    selectedIndex: Int,
    onLabel: String,
    offLabel: String,
    bottomBarLeft: List<Pair<String, String>>,
    bottomBarRight: List<Pair<String, String>>,
    fontSize: TextUnit = 22.sp,
    lineHeight: TextUnit = 32.sp,
) {
    val itemHeight = pillItemHeight(lineHeight, verticalPadding)

    ScreenBackground(backgroundImagePath = null, backgroundAlpha = 0.85f, backgroundColor = Color.Black) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(screenPadding)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = footerReservation())
            ) {
                ScreenTitle(text = title, fontSize = fontSize, lineHeight = lineHeight)
                Spacer(modifier = Modifier.height(Spacing.Sm))
                SectionedList(
                    sections = sections,
                    selectedIndex = selectedIndex,
                    fontSize = fontSize,
                    lineHeight = lineHeight,
                    verticalPadding = verticalPadding,
                    itemHeight = itemHeight
                ) { _, item, isSelected ->
                    Box(modifier = if (item.supported) Modifier else Modifier.alpha(0.4f)) {
                        PillRowKeyValue(
                            label = item.label,
                            value = if (item.enabled) onLabel else offLabel,
                            isSelected = isSelected,
                            fontSize = fontSize,
                            lineHeight = lineHeight,
                            verticalPadding = verticalPadding
                        )
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
