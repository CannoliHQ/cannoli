package dev.cannoli.scorza.ui.components

import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import dev.cannoli.ui.theme.Radius
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cannoli.scorza.R
import dev.cannoli.scorza.romm.download.DownloadStatus
import dev.cannoli.scorza.romm.download.RommDownloadItem
import dev.cannoli.scorza.romm.download.RommDownloadKind
import dev.cannoli.scorza.romm.download.inDisplayOrder
import dev.cannoli.scorza.ui.screens.DialogState
import dev.cannoli.scorza.ui.screens.KeyboardInputState
import dev.cannoli.scorza.romm.RommArtType
import dev.cannoli.ui.ButtonStyle
import dev.cannoli.ui.DPAD_HORIZONTAL
import dev.cannoli.ui.theme.LocalCannoliColors
import dev.cannoli.ui.theme.LocalCannoliFont
import dev.cannoli.ui.theme.LocalCannoliIconFont
import dev.cannoli.ui.components.BottomBar
import dev.cannoli.ui.components.QuickInfoOverlay
import dev.cannoli.ui.components.ColorPickerOverlay
import dev.cannoli.ui.components.HexColorInputOverlay
import dev.cannoli.ui.components.KeyboardOverlay
import dev.cannoli.ui.components.List
import dev.cannoli.ui.components.PillRowKeyValue
import dev.cannoli.ui.components.PillRowText
import dev.cannoli.ui.components.RAAccountOverlay
import dev.cannoli.ui.components.RALoggingInOverlay
import dev.cannoli.ui.components.RommConnectedOverlay
import dev.cannoli.ui.components.RommPairingOverlay
import dev.cannoli.ui.components.RestartOverlay
import dev.cannoli.ui.components.ScreenBackground
import dev.cannoli.ui.components.ScreenTitle
import dev.cannoli.ui.components.UpdateDownloadOverlay
import dev.cannoli.ui.components.footerReservation
import dev.cannoli.ui.components.pillItemHeight
import dev.cannoli.ui.components.screenPadding
import dev.cannoli.ui.theme.Spacing

private const val ICON_CHECK_CIRCLE = "\uF058"

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
    downloads: List<RommDownloadItem> = emptyList(),
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
                ) { _, option, isSelected ->
                    val parts = option.split("\t", limit = 2)
                    if (parts.size == 2) {
                        PillRowKeyValue(
                            label = parts[0],
                            value = parts[1],
                            isSelected = isSelected,
                            fontSize = listFontSize,
                            lineHeight = listLineHeight,
                            verticalPadding = listVerticalPadding
                        )
                    } else {
                        PillRowText(
                            label = option,
                            isSelected = isSelected,
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
                ) { _, option, isSelected ->
                    PillRowText(
                        label = option,
                        isSelected = isSelected,
                        fontSize = listFontSize,
                        lineHeight = listLineHeight,
                        verticalPadding = listVerticalPadding
                    )
                }
            }
        }

        is DialogState.ColorPicker -> {
            ColorPickerOverlay(
                title = dialogState.title,
                selectedRow = dialogState.selectedRow,
                selectedCol = dialogState.selectedCol,
                currentColor = dialogState.currentColor,
                titleFontSize = listFontSize,
                titleLineHeight = listLineHeight,
                buttonStyle = buttonStyle
            )
        }

        is DialogState.HexColorInput -> {
            HexColorInputOverlay(
                title = dialogState.title,
                currentHex = dialogState.currentHex,
                selectedIndex = dialogState.selectedIndex,
                titleFontSize = listFontSize,
                titleLineHeight = listLineHeight,
                buttonStyle = buttonStyle
            )
        }

        is DialogState.RenameInput,
        is DialogState.NewCollectionInput,
        is DialogState.CollectionRenameInput,
        is DialogState.NewFolderInput -> {
            val ks = dialogState as KeyboardInputState
            val keyboardTitle = (dialogState as? DialogState.RenameInput)?.let { rn ->
                when (rn.gameName) {
                    "launcher_global_search" -> stringResource(R.string.search_global)
                    "romm_global_search" -> stringResource(R.string.search_romm)
                    "launcher_search", "romm_search" -> rn.searchScope?.let { stringResource(R.string.search_in_platform, it) }
                    else -> null
                }
            }
            KeyboardOverlay(
                text = ks.currentName,
                cursorPos = ks.cursorPos,
                keyRow = ks.keyRow,
                keyCol = ks.keyCol,
                caps = ks.caps,
                symbols = ks.symbols,
                title = keyboardTitle,
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

        is DialogState.RommPairing -> {
            RommPairingOverlay(host = dialogState.host, message = dialogState.message, buttonStyle = buttonStyle)
        }
        is DialogState.RommConnected -> {
            RommConnectedOverlay(host = dialogState.host, username = dialogState.username, version = dialogState.version, buttonStyle = buttonStyle)
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

        is DialogState.IntentAuditResult -> {
            RestartOverlay(message = dialogState.message, buttonStyle = buttonStyle)
        }

        is DialogState.QuickMenu -> {
            ListDialogScreen(
                backgroundImagePath = backgroundImagePath,
                backgroundTint = backgroundTint,
                title = stringResource(R.string.quick_menu_title),
                listFontSize = listFontSize,
                listLineHeight = listLineHeight,
                rightBottomItems = listOf(buttonStyle.confirm to stringResource(R.string.label_select)),
                buttonStyle = buttonStyle
            ) {
                List(
                    items = dialogState.rows,
                    selectedIndex = dialogState.selectedIndex,
                    itemHeight = itemHeight
                ) { _, row, isSelected ->
                    PillRowText(
                        label = quickMenuLabel(row),
                        isSelected = isSelected,
                        fontSize = listFontSize,
                        lineHeight = listLineHeight,
                        verticalPadding = listVerticalPadding
                    )
                }
            }
        }
        is DialogState.QuickInfo -> {
            QuickInfoOverlay(
                urls = dialogState.urls,
                kitchenRunning = dialogState.kitchenRunning,
                selectedIndex = dialogState.selectedIndex,
                buttonStyle = buttonStyle
            )
        }

        is DialogState.RommActionsMenu -> {
            val rows = RommActionRow.visibleRows(dialogState.hasDownloads)
            ListDialogScreen(
                backgroundImagePath = backgroundImagePath,
                backgroundTint = backgroundTint,
                title = stringResource(R.string.romm_actions_title),
                listFontSize = listFontSize,
                listLineHeight = listLineHeight,
                rightBottomItems = listOf(buttonStyle.confirm to stringResource(R.string.label_select)),
                buttonStyle = buttonStyle
            ) {
                List(items = rows, selectedIndex = dialogState.selectedIndex, itemHeight = itemHeight) { _, row, isSelected ->
                    PillRowText(
                        label = stringResource(row.labelRes),
                        isSelected = isSelected,
                        fontSize = listFontSize,
                        lineHeight = listLineHeight,
                        verticalPadding = listVerticalPadding
                    )
                }
            }
        }

        is DialogState.RommSettingsMenu -> {
            val rows = RommSettingsRow.entries.toList()
            ListDialogScreen(
                backgroundImagePath = backgroundImagePath,
                backgroundTint = backgroundTint,
                title = stringResource(R.string.romm_settings_title),
                listFontSize = listFontSize,
                listLineHeight = listLineHeight,
                leftBottomItems = if (rows[dialogState.selectedIndex].isCycle)
                    listOf(DPAD_HORIZONTAL to stringResource(R.string.label_change)) else emptyList(),
                rightBottomItems = listOf(buttonStyle.confirm to stringResource(R.string.label_select)),
                buttonStyle = buttonStyle
            ) {
                List(items = rows, selectedIndex = dialogState.selectedIndex, itemHeight = itemHeight) { _, row, isSelected ->
                    when (row) {
                        RommSettingsRow.CONCURRENT -> PillRowKeyValue(
                            label = stringResource(R.string.romm_qm_concurrent),
                            value = dialogState.concurrent.toString(),
                            isSelected = isSelected,
                            fontSize = listFontSize,
                            lineHeight = listLineHeight,
                            verticalPadding = listVerticalPadding
                        )
                        RommSettingsRow.COVER_ART -> PillRowKeyValue(
                            label = stringResource(R.string.romm_qm_cover_art),
                            value = stringResource(rommArtLabelRes(dialogState.artType)),
                            isSelected = isSelected,
                            fontSize = listFontSize,
                            lineHeight = listLineHeight,
                            verticalPadding = listVerticalPadding
                        )
                        else -> PillRowText(
                            label = stringResource(row.labelRes),
                            isSelected = isSelected,
                            fontSize = listFontSize,
                            lineHeight = listLineHeight,
                            verticalPadding = listVerticalPadding
                        )
                    }
                }
            }
        }

        is DialogState.RommAdvancedMenu -> {
            ListDialogScreen(
                backgroundImagePath = backgroundImagePath,
                backgroundTint = backgroundTint,
                title = stringResource(R.string.romm_qm_advanced),
                listFontSize = listFontSize,
                listLineHeight = listLineHeight,
                rightBottomItems = listOf(buttonStyle.confirm to stringResource(R.string.label_select)),
                buttonStyle = buttonStyle
            ) {
                List(
                    items = ROMM_ADVANCED_ROWS,
                    selectedIndex = dialogState.selectedIndex,
                    itemHeight = itemHeight
                ) { _, labelRes, isSelected ->
                    PillRowText(
                        label = stringResource(labelRes),
                        isSelected = isSelected,
                        fontSize = listFontSize,
                        lineHeight = listLineHeight,
                        verticalPadding = listVerticalPadding
                    )
                }
            }
        }

        is DialogState.RommConfirm -> {
            val message = when (dialogState.action) {
                dev.cannoli.scorza.ui.screens.RommConfirmAction.REBUILD_CACHE -> R.string.romm_rebuild_confirm
                dev.cannoli.scorza.ui.screens.RommConfirmAction.DISCONNECT -> R.string.romm_disconnect_confirm
                dev.cannoli.scorza.ui.screens.RommConfirmAction.CANCEL_DOWNLOAD -> R.string.romm_cancel_confirm
                dev.cannoli.scorza.ui.screens.RommConfirmAction.CANCEL_ALL -> R.string.romm_cancel_all_confirm
            }
            ConfirmOverlay(
                message = stringResource(message),
                buttonStyle = buttonStyle
            )
        }

        is DialogState.RommPlatformToggle -> {
            val colors = LocalCannoliColors.current
            val font = LocalCannoliFont.current
            ListDialogScreen(
                backgroundImagePath = backgroundImagePath,
                backgroundTint = backgroundTint,
                title = stringResource(R.string.romm_platforms_title),
                listFontSize = listFontSize,
                listLineHeight = listLineHeight,
                rightBottomItems = listOf(buttonStyle.confirm to stringResource(R.string.label_toggle)),
                buttonStyle = buttonStyle
            ) {
                if (dialogState.items.isEmpty()) {
                    Text(stringResource(R.string.romm_platforms_empty), color = colors.text.copy(alpha = 0.5f), fontFamily = font, fontSize = listFontSize)
                } else {
                    List(
                        items = dialogState.items,
                        selectedIndex = dialogState.selectedIndex,
                        itemHeight = itemHeight
                    ) { _, item, isSelected ->
                        PillRowText(
                            label = item.displayName,
                            isSelected = isSelected,
                            checkState = item.visible,
                            fontSize = listFontSize,
                            lineHeight = listLineHeight,
                            verticalPadding = listVerticalPadding
                        )
                    }
                }
            }
        }

        is DialogState.RescanProgress -> {
            dev.cannoli.scorza.ui.screens.HousekeepingScreen(
                kind = dev.cannoli.scorza.ui.screens.HousekeepingKind.LIBRARY_REFRESH,
                progress = dialogState.progress,
                statusLabel = dialogState.label,
            )
        }

        is DialogState.RommArtResults -> {
            dev.cannoli.scorza.ui.screens.RommArtResultsScreen(
                results = dialogState.results,
                selectedIndex = dialogState.selectedIndex,
                backgroundImagePath = null,
                backgroundTint = backgroundTint,
                listFontSize = listFontSize,
                listLineHeight = listLineHeight,
                listVerticalPadding = listVerticalPadding,
                buttonStyle = buttonStyle,
            )
        }

        is DialogState.RommDownloads -> {
            val colors = LocalCannoliColors.current
            val font = LocalCannoliFont.current
            val ordered = downloads.inDisplayOrder()
            val firstDoneIndex = ordered.indexOfFirst { it.status == DownloadStatus.Done }
            val confirmLabel = when (ordered.getOrNull(dialogState.selectedIndex)?.status) {
                is DownloadStatus.Failed -> R.string.label_retry
                DownloadStatus.Queued, is DownloadStatus.Downloading -> R.string.label_cancel
                else -> null
            }
            val activeCount = downloads.count {
                it.status == DownloadStatus.Queued || it.status is DownloadStatus.Downloading
            }
            ListDialogScreen(
                backgroundImagePath = null,
                backgroundTint = backgroundTint,
                title = stringResource(R.string.romm_downloads_title),
                listFontSize = listFontSize,
                listLineHeight = listLineHeight,
                rightBottomItems = buildList {
                    if (confirmLabel != null) add(buttonStyle.confirm to stringResource(confirmLabel))
                    if (activeCount >= 2) add(buttonStyle.north to stringResource(R.string.label_cancel_all))
                },
                buttonStyle = buttonStyle,
            ) {
                if (ordered.isEmpty()) {
                    Text(stringResource(R.string.romm_download_empty), color = colors.text.copy(alpha = 0.5f), fontFamily = font, fontSize = listFontSize)
                } else {
                    List(items = ordered, selectedIndex = dialogState.selectedIndex, itemHeight = itemHeight) { index, item, isSelected ->
                        if (index == firstDoneIndex) {
                            DownloadSectionHeader(stringResource(R.string.romm_download_completed), listFontSize)
                        }
                        DownloadRow(item, isSelected, listFontSize)
                    }
                }
            }
        }

        else -> {}
    }
}

@Composable
private fun DownloadSectionHeader(text: String, fontSize: TextUnit) {
    val colors = LocalCannoliColors.current
    val font = LocalCannoliFont.current
    Text(
        text = text.uppercase(),
        color = colors.text.copy(alpha = 0.45f),
        fontFamily = font,
        fontSize = (fontSize.value * 0.7f).sp,
        letterSpacing = 1.5.sp,
        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun DownloadRow(item: RommDownloadItem, isSelected: Boolean, fontSize: TextUnit) {
    val colors = LocalCannoliColors.current
    val font = LocalCannoliFont.current
    val text = if (isSelected) colors.highlightText else colors.text
    val muted = text.copy(alpha = 0.55f)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .then(if (isSelected) Modifier.clip(Radius.Pill).background(colors.highlight) else Modifier)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            val label = if (item.kind == RommDownloadKind.MANUAL)
                "${item.displayName}  ·  ${stringResource(R.string.romm_download_manual)}"
            else item.displayName
            Text(label, color = text, fontFamily = font, fontSize = fontSize, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            val context = androidx.compose.ui.platform.LocalContext.current
            if (item.status == DownloadStatus.Done) {
                Text(ICON_CHECK_CIRCLE, color = text, fontFamily = LocalCannoliIconFont.current, fontSize = (fontSize.value * 0.9f).sp)
            } else {
                val right = when (val s = item.status) {
                    is DownloadStatus.Downloading ->
                        if (s.total > 0) stringResource(R.string.download_percent, (s.downloaded * 100 / s.total).coerceAtMost(100))
                        else if (s.downloaded > 0) android.text.format.Formatter.formatShortFileSize(context, s.downloaded)
                        else stringResource(R.string.romm_download_downloading)
                    DownloadStatus.Queued -> stringResource(R.string.romm_download_queued)
                    is DownloadStatus.Failed -> stringResource(R.string.romm_download_failed)
                    else -> ""
                }
                Text(right, color = muted, fontFamily = font, fontSize = (fontSize.value * 0.8f).sp)
            }
        }
    }
}

@Composable
private fun quickMenuLabel(row: dev.cannoli.scorza.ui.quickmenu.QuickMenuRow): String = when (row) {
    dev.cannoli.scorza.ui.quickmenu.QuickMenuRow.ROMM -> stringResource(R.string.quick_menu_romm)
    dev.cannoli.scorza.ui.quickmenu.QuickMenuRow.KITCHEN -> stringResource(R.string.quick_menu_kitchen)
    dev.cannoli.scorza.ui.quickmenu.QuickMenuRow.RESCAN -> stringResource(R.string.quick_menu_rescan)
    dev.cannoli.scorza.ui.quickmenu.QuickMenuRow.INFO -> stringResource(R.string.quick_menu_info)
}

val ROMM_ADVANCED_ROWS = listOf(R.string.romm_qm_refresh, R.string.romm_qm_rebuild, R.string.romm_qm_download_art)

enum class RommActionRow(@androidx.annotation.StringRes val labelRes: Int) {
    DOWNLOADS(R.string.label_downloads),
    RETURN_TO_CANNOLI(R.string.romm_return_to_cannoli),
    ;
    companion object {
        fun visibleRows(hasDownloads: Boolean): List<RommActionRow> =
            if (hasDownloads) entries else entries.filterNot { it == DOWNLOADS }
    }
}

enum class RommSettingsRow(@androidx.annotation.StringRes val labelRes: Int, val isCycle: Boolean = false) {
    COVER_ART(R.string.romm_qm_cover_art, isCycle = true),
    CONCURRENT(R.string.romm_qm_concurrent, isCycle = true),
    PLATFORMS(R.string.romm_qm_platforms),
    ADVANCED(R.string.romm_qm_advanced),
    SERVER_INFO(R.string.romm_qm_server_info),
}

@androidx.annotation.StringRes
fun rommArtLabelRes(artType: RommArtType): Int = when (artType) {
    RommArtType.DEFAULT -> R.string.romm_art_default
    RommArtType.NONE -> R.string.romm_art_off
    RommArtType.BOX2D -> R.string.romm_art_box2d
    RommArtType.BOX3D -> R.string.romm_art_box3d
    RommArtType.MIX -> R.string.romm_art_mix
    RommArtType.TITLE -> R.string.romm_art_title
    RommArtType.SCREENSHOT -> R.string.romm_art_screenshot
    RommArtType.MARQUEE -> R.string.romm_art_marquee
}

@Composable
private fun ConfirmOverlay(message: String, buttonStyle: ButtonStyle) {
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
            modifier = Modifier.padding(horizontal = 32.dp)
        )
        BottomBar(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(screenPadding),
            leftItems = listOf(buttonStyle.back to stringResource(R.string.label_cancel)),
            rightItems = listOf(buttonStyle.confirm to stringResource(R.string.label_select))
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
                    .then(if (fullWidth) Modifier.fillMaxSize() else Modifier.widthIn(max = 560.dp).fillMaxWidth())
                    .padding(bottom = footerReservation())
            ) {
                ScreenTitle(
                    text = title,
                    fontSize = listFontSize,
                    lineHeight = listLineHeight
                )
                Spacer(modifier = Modifier.height(Spacing.Sm))
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
