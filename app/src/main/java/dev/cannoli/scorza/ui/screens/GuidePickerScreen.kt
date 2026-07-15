package dev.cannoli.scorza.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import dev.cannoli.igm.GuideFile
import dev.cannoli.scorza.ui.components.ListDialogScreen
import dev.cannoli.ui.ButtonStyle
import dev.cannoli.ui.R
import dev.cannoli.ui.components.List
import dev.cannoli.ui.components.PillRowText
import dev.cannoli.ui.components.pillItemHeight

@Composable
fun GuidePickerScreen(
    files: List<GuideFile>,
    selectedIndex: Int,
    backgroundImagePath: String?,
    backgroundTint: Int,
    listFontSize: TextUnit,
    listLineHeight: TextUnit,
    listVerticalPadding: Dp,
    buttonStyle: ButtonStyle,
) {
    val itemHeight = pillItemHeight(listLineHeight, listVerticalPadding)
    Box(modifier = Modifier.fillMaxSize()) {
        ListDialogScreen(
            backgroundImagePath = backgroundImagePath,
            backgroundTint = backgroundTint,
            title = stringResource(R.string.title_guide),
            listFontSize = listFontSize,
            listLineHeight = listLineHeight,
            rightBottomItems = listOf(buttonStyle.confirm to stringResource(R.string.label_select)),
            buttonStyle = buttonStyle,
        ) {
            List(
                items = files,
                selectedIndex = selectedIndex,
                itemHeight = itemHeight,
            ) { _, file, isSelected ->
                PillRowText(
                    label = file.name,
                    isSelected = isSelected,
                    fontSize = listFontSize,
                    lineHeight = listLineHeight,
                    verticalPadding = listVerticalPadding,
                )
            }
        }
    }
}
