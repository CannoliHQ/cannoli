package dev.cannoli.scorza.ui.components

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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import dev.cannoli.scorza.R
import dev.cannoli.igm.ButtonStyle
import dev.cannoli.igm.ui.components.BottomBar
import dev.cannoli.igm.ui.components.List
import dev.cannoli.igm.ui.components.PillRowKeyValue
import dev.cannoli.igm.ui.components.PillRowText
import dev.cannoli.igm.ui.components.ScreenBackground
import dev.cannoli.igm.ui.components.ScreenTitle
import dev.cannoli.igm.ui.components.pillItemHeight
import dev.cannoli.igm.ui.components.screenPadding
import dev.cannoli.scorza.ui.screens.DialogState
import dev.cannoli.scorza.ui.screens.KeyboardInputState

@Composable
fun DialogOverlay(
    dialogState: DialogState,
    backgroundImagePath: String?,
    backgroundTint: Int,
    listFontSize: TextUnit,
    listLineHeight: TextUnit,
    listVerticalPadding: Dp,
    downloadProgress: Float = 0f,
    downloadError: String? = null,
    updateAvailable: Boolean = false,
    buttonStyle: ButtonStyle = ButtonStyle()
) {
    val itemHeight = pillItemHeight(listLineHeight, listVerticalPadding)
    when (dialogState) {
        is DialogState.ContextMenu -> {
            ListDialogScreen(
                backgroundImagePath = backgroundImagePath,
                backgroundTint = backgroundTint,
                title = dialogState.gameName,
                listFontSize = listFontSize,
                listLineHeight = listLineHeight,
                fullWidth = dialogState.options.any { it.contains('\t') },
                rightBottomItems = emptyList(),
                buttonStyle = buttonStyle
            ) {
                List(
                    items = dialogState.options,
                    selectedIndex = dialogState.selectedOption,
                    itemHeight = itemHeight
                ) { index, option ->
                    val parts = option.split("\t", limit = 2)
                    if (parts.size == 2) {
                        PillRowKeyValue(
                            label = parts[0],
                            value = parts[1],
                            isSelected = dialogState.selectedOption == index,
                            fontSize = listFontSize,
                            lineHeight = listLineHeight,
                            verticalPadding = listVerticalPadding
                        )
                    } else {
                        PillRowText(
                            label = option,
                            isSelected = dialogState.selectedOption == index,
                            fontSize = listFontSize,
                            lineHeight = listLineHeight,
                            verticalPadding = listVerticalPadding
                        )
                    }
                }
            }
        }

        is DialogState.BulkContextMenu -> {
            ListDialogScreen(
                backgroundImagePath = backgroundImagePath,
                backgroundTint = backgroundTint,
                title = stringResource(R.string.selected_count, dialogState.gamePaths.size),
                listFontSize = listFontSize,
                listLineHeight = listLineHeight,
                rightBottomItems = emptyList(),
                buttonStyle = buttonStyle
            ) {
                List(
                    items = dialogState.options,
                    selectedIndex = dialogState.selectedOption,
                    itemHeight = itemHeight
                ) { index, option ->
                    PillRowText(
                        label = option,
                        isSelected = dialogState.selectedOption == index,
                        fontSize = listFontSize,
                        lineHeight = listLineHeight,
                        verticalPadding = listVerticalPadding
                    )
                }
            }
        }

        is DialogState.ColorPicker -> {
            ColorPickerOverlay(
                selectedRow = dialogState.selectedRow,
                selectedCol = dialogState.selectedCol,
                currentColor = dialogState.currentColor,
                buttonStyle = buttonStyle
            )
        }

        is DialogState.HexColorInput -> {
            HexColorInputOverlay(
                currentHex = dialogState.currentHex,
                selectedIndex = dialogState.selectedIndex,
                buttonStyle = buttonStyle
            )
        }

        is DialogState.RenameInput,
        is DialogState.NewCollectionInput,
        is DialogState.CollectionRenameInput,
        is DialogState.ProfileNameInput,
        is DialogState.NewFolderInput -> {
            val ks = dialogState as KeyboardInputState
            KeyboardOverlay(
                text = ks.currentName,
                cursorPos = ks.cursorPos,
                keyRow = ks.keyRow,
                keyCol = ks.keyCol,
                caps = ks.caps,
                symbols = ks.symbols,
                buttonStyle = buttonStyle
            )
        }

        is DialogState.About -> {
            AboutOverlay(statusMessage = dialogState.statusMessage, updateAvailable = updateAvailable, buttonStyle = buttonStyle)
        }

        is DialogState.Kitchen -> {
            KitchenOverlay(
                urls = dialogState.urls,
                selectedIndex = dialogState.selectedIndex,
                pin = dialogState.pin,
                requirePin = dialogState.requirePin,
                buttonStyle = buttonStyle
            )
        }

        is DialogState.RAAccount -> {
            RAAccountOverlay(username = dialogState.username, buttonStyle = buttonStyle)
        }

        is DialogState.RALoggingIn -> {
            RALoggingInOverlay(message = dialogState.message, buttonStyle = buttonStyle)
        }

        is DialogState.UpdateDownload -> {
            UpdateDownloadOverlay(
                versionName = dialogState.versionName,
                changelog = dialogState.changelog,
                progress = downloadProgress,
                error = downloadError,
                buttonStyle = buttonStyle
            )
        }

        is DialogState.RestartRequired -> {
            RestartOverlay(message = stringResource(R.string.restart_required), buttonStyle = buttonStyle)
        }

        else -> {}
    }
}

@Composable
internal fun ListDialogScreen(
    backgroundImagePath: String?,
    backgroundTint: Int,
    title: String,
    listFontSize: TextUnit,
    listLineHeight: TextUnit,
    fullWidth: Boolean = false,
    leftBottomItems: List<Pair<String, String>> = emptyList(),
    rightBottomItems: List<Pair<String, String>>,
    buttonStyle: ButtonStyle = ButtonStyle(),
    showBackButton: Boolean = true,
    content: @Composable () -> Unit
) {
    ScreenBackground(backgroundImagePath = backgroundImagePath, backgroundTint = backgroundTint) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(screenPadding)
        ) {
            Column(
                modifier = Modifier
                    .then(if (fullWidth) Modifier.fillMaxSize() else Modifier.fillMaxWidth(0.65f))
                    .padding(bottom = 48.dp)
            ) {
                ScreenTitle(
                    text = title,
                    fontSize = listFontSize,
                    lineHeight = listLineHeight
                )
                Spacer(modifier = Modifier.height(8.dp))
                content()
            }
            val left = if (showBackButton) listOf(buttonStyle.back to stringResource(R.string.label_back)) + leftBottomItems else leftBottomItems
            BottomBar(
                modifier = Modifier.align(Alignment.BottomCenter),
                leftItems = left,
                rightItems = rightBottomItems
            )
        }
    }
}
