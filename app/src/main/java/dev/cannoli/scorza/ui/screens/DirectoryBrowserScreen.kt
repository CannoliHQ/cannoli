package dev.cannoli.scorza.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import dev.cannoli.igm.ButtonStyle
import dev.cannoli.igm.ui.components.List
import dev.cannoli.igm.ui.components.PillRowText
import dev.cannoli.scorza.ui.components.ListDialogScreen

@Composable
fun DirectoryBrowserScreen(
    currentPath: String,
    entries: List<String>,
    selectedIndex: Int,
    scrollTarget: Int,
    backgroundImagePath: String?,
    backgroundTint: Int,
    listFontSize: TextUnit,
    listLineHeight: TextUnit,
    listVerticalPadding: Dp,
    itemHeight: Dp,
    isSelectRow: Boolean,
    showSelectOption: Boolean = true,
    showNewFolder: Boolean = true,
    onVisibleRangeChanged: (Int, Int, Boolean) -> Unit,
    buttonStyle: ButtonStyle
) {
    val displayItems = if (showSelectOption) listOf("Use this location") + entries else entries

    val rightItems = buildList {
        if (showNewFolder && showSelectOption) add(buttonStyle.north to "NEW FOLDER")
        if (showSelectOption && isSelectRow) {
            add(buttonStyle.confirm to "SELECT")
        } else {
            add(buttonStyle.confirm to "OPEN")
        }
    }

    ListDialogScreen(
        backgroundImagePath = backgroundImagePath,
        backgroundTint = backgroundTint,
        title = currentPath,
        listFontSize = listFontSize,
        listLineHeight = listLineHeight,
        fullWidth = true,
        showBackButton = false,
        leftBottomItems = buildList {
            add(buttonStyle.west to "CANCEL")
            if (showSelectOption) add(buttonStyle.back to "DIR UP")
        },
        rightBottomItems = rightItems,
        buttonStyle = buttonStyle
    ) {
        List(
            items = displayItems,
            selectedIndex = selectedIndex,
            itemHeight = itemHeight,
            scrollTarget = scrollTarget,
            onVisibleRangeChanged = onVisibleRangeChanged
        ) { index, item ->
            PillRowText(
                label = item,
                isSelected = selectedIndex == index,
                fontSize = listFontSize,
                lineHeight = listLineHeight,
                verticalPadding = listVerticalPadding
            )
        }
    }
}
