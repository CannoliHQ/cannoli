package dev.cannoli.scorza.ui.components

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import dev.cannoli.scorza.R
import dev.cannoli.scorza.download.DownloadStatus
import dev.cannoli.scorza.download.DownloadItem
import dev.cannoli.scorza.download.DownloadKind
import dev.cannoli.scorza.download.inDisplayOrder
import dev.cannoli.scorza.ui.screens.DialogState
import dev.cannoli.ui.ButtonStyle
import dev.cannoli.ui.DPAD_HORIZONTAL
import dev.cannoli.ui.theme.CannoliIcons
import dev.cannoli.ui.theme.LocalCannoliColors
import dev.cannoli.ui.theme.LocalCannoliFont
import dev.cannoli.ui.theme.LocalCannoliIconFont
import dev.cannoli.ui.components.List
import dev.cannoli.ui.components.ListSection
import dev.cannoli.ui.components.PillRowInfo
import dev.cannoli.ui.components.PillRowKeyValue
import dev.cannoli.ui.components.PillRowText
import dev.cannoli.ui.components.SectionedList
import dev.cannoli.ui.components.RommConnectedOverlay
import dev.cannoli.ui.components.RommPairingOverlay

@Composable
internal fun RommDialogs(
    dialogState: DialogState,
    backgroundImagePath: String?,
    backgroundTint: Int,
    listFontSize: TextUnit,
    listLineHeight: TextUnit,
    listVerticalPadding: Dp,
    downloads: List<DownloadItem>,
    buttonStyle: ButtonStyle,
    itemHeight: Dp,
) {
    when (dialogState) {
        is DialogState.RommPairing -> {
            RommPairingOverlay(
                host = dialogState.host,
                message = dialogState.message,
                waitingApproval = dialogState.waitingApproval,
                qrBitmap = dialogState.qrBitmap,
                buttonStyle = buttonStyle,
            )
        }
        is DialogState.RommConnected -> {
            RommConnectedOverlay(host = dialogState.host, username = dialogState.username, version = dialogState.version, buttonStyle = buttonStyle)
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

        is DialogState.RommVersionPicker -> {
            ListDialogScreen(
                backgroundImagePath = backgroundImagePath,
                backgroundTint = backgroundTint,
                title = stringResource(dev.cannoli.scorza.R.string.romm_version_picker_title),
                listFontSize = listFontSize,
                listLineHeight = listLineHeight,
                rightBottomItems = listOf(
                    buttonStyle.confirm to stringResource(dev.cannoli.scorza.R.string.label_download)
                ),
                buttonStyle = buttonStyle,
            ) {
                List(items = dialogState.members, selectedIndex = dialogState.selectedIndex, itemHeight = itemHeight) { _, entry, isSelected ->
                    PillRowKeyValue(
                        label = if (entry.isPrimary) "${CannoliIcons.Primary.glyph} ${entry.label}" else entry.label,
                        value = dev.cannoli.scorza.ui.screens.RommGameDetailLayout.formatBytes(entry.game.sizeBytes),
                        isSelected = isSelected,
                        fontSize = listFontSize,
                        lineHeight = listLineHeight,
                        verticalPadding = listVerticalPadding,
                        dotIndicator = if (entry.present) true else null,
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
                            label = stringResource(R.string.romm_settings_concurrent),
                            value = dialogState.concurrent.toString(),
                            isSelected = isSelected,
                            fontSize = listFontSize,
                            lineHeight = listLineHeight,
                            verticalPadding = listVerticalPadding
                        )
                        RommSettingsRow.COVER_ART -> PillRowKeyValue(
                            label = stringResource(R.string.romm_settings_cover_art),
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
                title = stringResource(R.string.romm_settings_advanced),
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

        is DialogState.RommCollectionToggle -> {
            ListDialogScreen(
                backgroundImagePath = backgroundImagePath,
                backgroundTint = backgroundTint,
                title = stringResource(R.string.romm_collections_title),
                listFontSize = listFontSize,
                listLineHeight = listLineHeight,
                rightBottomItems = listOf(buttonStyle.confirm to stringResource(R.string.label_toggle)),
                buttonStyle = buttonStyle
            ) {
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
                title = stringResource(R.string.romm_download_queue),
                listFontSize = listFontSize,
                listLineHeight = listLineHeight,
                rightBottomItems = buildList {
                    if (confirmLabel != null) add(buttonStyle.confirm to stringResource(confirmLabel))
                    if (activeCount >= 2) add(buttonStyle.north to stringResource(R.string.label_cancel_all))
                    // Its own button rather than sharing north: a queue can hold finished rows and
                    // active ones at the same time, so clearing and cancelling have to coexist.
                    if (ordered.any { it.status == DownloadStatus.Done || it.status is DownloadStatus.Failed }) {
                        add(buttonStyle.west to stringResource(R.string.label_clear_finished))
                    }
                },
                buttonStyle = buttonStyle,
            ) {
                if (ordered.isEmpty()) {
                    Text(stringResource(R.string.romm_download_empty), color = colors.text.copy(alpha = 0.5f), fontFamily = font, fontSize = listFontSize)
                } else {
                    val active = if (firstDoneIndex < 0) ordered else ordered.subList(0, firstDoneIndex)
                    val done = if (firstDoneIndex < 0) emptyList() else ordered.subList(firstDoneIndex, ordered.size)
                    val completedHeader = stringResource(R.string.romm_download_completed).uppercase()
                    val sections = buildList {
                        if (active.isNotEmpty()) add(ListSection(header = null, items = active))
                        if (done.isNotEmpty()) add(ListSection(header = completedHeader, items = done))
                    }
                    SectionedList(
                        sections = sections,
                        selectedIndex = dialogState.selectedIndex,
                        fontSize = listFontSize,
                        lineHeight = listLineHeight,
                        verticalPadding = listVerticalPadding,
                        itemHeight = itemHeight,
                    ) { _, item, isSelected ->
                        DownloadRow(item, isSelected, listFontSize, listLineHeight, listVerticalPadding)
                    }
                }
            }
        }

        else -> {}
    }
}

@Composable
private fun DownloadRow(item: DownloadItem, isSelected: Boolean, fontSize: TextUnit, lineHeight: TextUnit, verticalPadding: Dp) {
    val colors = LocalCannoliColors.current
    val font = LocalCannoliFont.current
    val text = if (isSelected) colors.highlightText else colors.text
    val context = androidx.compose.ui.platform.LocalContext.current
    val label = if (item.kind == DownloadKind.MANUAL)
        "${item.displayName}  ·  ${stringResource(R.string.romm_download_manual)}"
    else item.displayName
    PillRowInfo(
        label = label,
        isSelected = isSelected,
        fontSize = fontSize,
        lineHeight = lineHeight,
        verticalPadding = verticalPadding
    ) {
        if (item.status == DownloadStatus.Done) {
            Text(CannoliIcons.CheckCircle.glyph, color = text, fontFamily = LocalCannoliIconFont.current, fontSize = (fontSize.value * 0.9f).sp, lineHeight = lineHeight)
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
            // Same colour as the label it sits beside, and the row's own lineHeight: without it the
            // smaller type falls back to its own default and rides high in the pill instead of
            // centring against the name.
            Text(
                right,
                color = text,
                fontFamily = font,
                fontSize = (fontSize.value * 0.8f).sp,
                lineHeight = lineHeight,
            )
        }
    }
}

