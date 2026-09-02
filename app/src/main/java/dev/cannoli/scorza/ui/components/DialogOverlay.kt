package dev.cannoli.scorza.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import dev.cannoli.scorza.R
import dev.cannoli.scorza.download.DownloadItem
import dev.cannoli.scorza.ui.screens.DialogState
import dev.cannoli.ui.ButtonStyle
import dev.cannoli.ui.DPAD_HORIZONTAL
import dev.cannoli.ui.theme.LocalCannoliColors
import dev.cannoli.ui.theme.LocalCannoliFont
import dev.cannoli.ui.components.BottomBar
import dev.cannoli.ui.components.List
import dev.cannoli.ui.components.PillRowKeyValue
import dev.cannoli.ui.components.PillRowText
import dev.cannoli.ui.components.ScreenBackground
import dev.cannoli.ui.components.ScreenTitle
import dev.cannoli.ui.components.footerReservation
import dev.cannoli.ui.components.listTitleSpacing
import dev.cannoli.ui.components.pillItemHeight
import dev.cannoli.ui.components.screenPadding

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
    coreUpdate: dev.cannoli.scorza.launcher.CoreDownloadService.UpdateProgress? = null,
    downloads: List<DownloadItem> = emptyList(),
    updateAvailable: Boolean = false,
    buttonStyle: ButtonStyle = ButtonStyle(),
    // Only the Tools and Ports lists offer to drop a shortcut for an app that has gone missing,
    // and that depends on the list being viewed rather than on anything the dialog carries.
    appListPlatformTag: String? = null,
) {
    val itemHeight = pillItemHeight(listLineHeight, listVerticalPadding)
    LibraryDialogs(
        dialogState = dialogState,
        backgroundImagePath = backgroundImagePath,
        backgroundTint = backgroundTint,
        listFontSize = listFontSize,
        listLineHeight = listLineHeight,
        listVerticalPadding = listVerticalPadding,
        buttonStyle = buttonStyle,
        appListPlatformTag = appListPlatformTag,
        itemHeight = itemHeight,
    )
    SystemDialogs(
        dialogState = dialogState,
        backgroundImagePath = backgroundImagePath,
        backgroundTint = backgroundTint,
        listFontSize = listFontSize,
        listLineHeight = listLineHeight,
        listVerticalPadding = listVerticalPadding,
        downloadProgress = downloadProgress,
        downloadError = downloadError,
        coreUpdate = coreUpdate,
        updateAvailable = updateAvailable,
        buttonStyle = buttonStyle,
        itemHeight = itemHeight,
    )
    RommDialogs(
        dialogState = dialogState,
        backgroundImagePath = backgroundImagePath,
        backgroundTint = backgroundTint,
        listFontSize = listFontSize,
        listLineHeight = listLineHeight,
        listVerticalPadding = listVerticalPadding,
        downloads = downloads,
        buttonStyle = buttonStyle,
        itemHeight = itemHeight,
    )
    SaveSyncDialogs(
        dialogState = dialogState,
        backgroundImagePath = backgroundImagePath,
        backgroundTint = backgroundTint,
        listFontSize = listFontSize,
        listLineHeight = listLineHeight,
        listVerticalPadding = listVerticalPadding,
        buttonStyle = buttonStyle,
        itemHeight = itemHeight,
    )
    if (dialogState is DialogState.Picker) {
        PickerDialog(
            dialogState = dialogState,
            backgroundImagePath = backgroundImagePath,
            backgroundTint = backgroundTint,
            listFontSize = listFontSize,
            listLineHeight = listLineHeight,
            listVerticalPadding = listVerticalPadding,
            buttonStyle = buttonStyle,
            itemHeight = itemHeight,
        )
    }
}

/** Draws any [DialogState.Picker]. Sits outside the four families because it belongs to none. */
@Composable
private fun PickerDialog(
    dialogState: DialogState.Picker,
    backgroundImagePath: String?,
    backgroundTint: Int,
    listFontSize: TextUnit,
    listLineHeight: TextUnit,
    listVerticalPadding: Dp,
    buttonStyle: ButtonStyle,
    itemHeight: Dp,
) {
    // The legend follows the selected row, which is the rule four screens had each written out.
    val selectedRow = dialogState.items.getOrNull(dialogState.selectedIndex)
    val cycles = selectedRow?.cycles == true
    val clears = selectedRow?.clears == true
    ListDialogScreen(
        backgroundImagePath = backgroundImagePath,
        backgroundTint = backgroundTint,
        // A picker opened from a context menu is titled after the row that opened it, and those
        // rows carry a stable key rather than words. Anything with no entry passes through, which
        // is what pickers built from already-translated titles rely on.
        title = dev.cannoli.scorza.input.MENU_LABELS[dialogState.title]
            ?.let { stringResource(it) } ?: dialogState.title,
        listFontSize = listFontSize,
        listLineHeight = listLineHeight,
        leftBottomItems = buildList {
            if (cycles) add(DPAD_HORIZONTAL to stringResource(R.string.label_change))
            if (clears) add(buttonStyle.north to stringResource(R.string.label_clear))
        },
        rightBottomItems = listOf(buttonStyle.confirm to dialogState.confirmLabel),
        buttonStyle = buttonStyle,
    ) {
        val empty = dialogState.emptyMessage
        if (dialogState.items.isEmpty() && empty != null) {
            Text(
                text = empty,
                color = LocalCannoliColors.current.text.copy(alpha = 0.5f),
                fontFamily = LocalCannoliFont.current,
                fontSize = listFontSize,
            )
            return@ListDialogScreen
        }
        List(items = dialogState.items, selectedIndex = dialogState.selectedIndex, itemHeight = itemHeight) { _, item, isSelected ->
            // A row with no value is a text row, not a key-value row with a blank value: the
            // highlight hugs the label on one and runs full width on the other, so passing an
            // empty string here would quietly restyle every picker that carries labels alone.
            if (item.value == null && item.dot == null) {
                PillRowText(
                    label = item.label,
                    isSelected = isSelected,
                    checkState = item.checked,
                    fontSize = listFontSize,
                    lineHeight = listLineHeight,
                    verticalPadding = listVerticalPadding,
                )
            } else {
                PillRowKeyValue(
                    label = item.label,
                    value = item.value.orEmpty(),
                    isSelected = isSelected,
                    fontSize = listFontSize,
                    lineHeight = listLineHeight,
                    verticalPadding = listVerticalPadding,
                    dotIndicator = item.dot,
                )
            }
        }
    }
}

@Composable
internal fun ConfirmOverlay(
    message: String,
    buttonStyle: ButtonStyle,
    cancelLabel: String? = null,
    confirmLabel: String? = null,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(androidx.compose.ui.graphics.Color.Black),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = message,
            color = LocalCannoliColors.current.text,
            fontFamily = LocalCannoliFont.current,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.padding(horizontal = 32.dp)
        )
        BottomBar(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(screenPadding),
            leftItems = listOf(buttonStyle.back to (cancelLabel ?: stringResource(R.string.label_cancel))),
            rightItems = listOf(buttonStyle.confirm to (confirmLabel ?: stringResource(R.string.label_confirm)))
        )
    }
}

@Composable
internal fun ListDialogScreen(
    backgroundImagePath: String?,
    backgroundTint: Int,
    title: String,
    listFontSize: TextUnit,
    listLineHeight: TextUnit,
    fullWidth: Boolean = true,
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
                    .then(if (fullWidth) Modifier.fillMaxSize() else Modifier.widthIn(max = 560.dp).fillMaxWidth())
                    .padding(bottom = footerReservation())
            ) {
                ScreenTitle(
                    text = title,
                    fontSize = listFontSize,
                    lineHeight = listLineHeight
                )
                Spacer(modifier = Modifier.height(listTitleSpacing()))
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
