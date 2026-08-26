package dev.cannoli.scorza.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cannoli.scorza.R
import dev.cannoli.scorza.input.MENU_FORCE_SOFTCORE
import dev.cannoli.scorza.romm.sync.PreLaunchOutcome
import dev.cannoli.scorza.romm.sync.SyncDirection
import dev.cannoli.scorza.ui.screens.SyncHistoryRow
import dev.cannoli.scorza.download.DownloadStatus
import dev.cannoli.scorza.download.DownloadItem
import dev.cannoli.scorza.download.DownloadKind
import dev.cannoli.scorza.download.inDisplayOrder
import dev.cannoli.scorza.ui.screens.ConflictChoice
import dev.cannoli.scorza.ui.screens.DialogState
import dev.cannoli.scorza.ui.screens.KeyboardHost
import dev.cannoli.scorza.ui.screens.RaTokenState
import dev.cannoli.scorza.romm.RommArtType
import dev.cannoli.ui.ButtonStyle
import dev.cannoli.ui.DPAD_HORIZONTAL
import dev.cannoli.ui.START_GLYPH
import dev.cannoli.ui.theme.CannoliIcons
import dev.cannoli.ui.theme.LocalCannoliColors
import dev.cannoli.ui.theme.LocalCannoliFont
import dev.cannoli.ui.theme.LocalCannoliIconFont
import dev.cannoli.ui.components.BottomBar
import dev.cannoli.ui.components.QuickInfoOverlay
import dev.cannoli.ui.components.ColorPickerOverlay
import dev.cannoli.ui.components.HexColorInputOverlay
import dev.cannoli.ui.components.KeyboardHelpOverlay
import dev.cannoli.ui.components.KeyboardOverlay
import dev.cannoli.ui.components.LaunchIssue
import dev.cannoli.ui.components.List
import dev.cannoli.ui.components.ListSection
import dev.cannoli.ui.components.PillRowInfo
import dev.cannoli.ui.components.PillRowKeyValue
import dev.cannoli.ui.components.PillRowText
import dev.cannoli.ui.components.MessageOverlay
import dev.cannoli.ui.components.OverlayScrim
import dev.cannoli.ui.components.SectionedList
import dev.cannoli.ui.components.RALoggingInOverlay
import dev.cannoli.ui.components.RommConnectedOverlay
import dev.cannoli.ui.components.RommPairingOverlay
import dev.cannoli.ui.components.RestartOverlay
import dev.cannoli.ui.components.ScreenBackground
import dev.cannoli.ui.components.ScreenTitle
import dev.cannoli.ui.components.UpdateDownloadOverlay
import dev.cannoli.ui.components.footerReservation
import dev.cannoli.ui.components.listTitleSpacing
import dev.cannoli.ui.components.pillItemHeight
import dev.cannoli.ui.components.screenPadding
import dev.cannoli.ui.theme.Spacing


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
    when (dialogState) {
        is DialogState.ContextMenu -> {
            val selected = dialogState.options.getOrNull(dialogState.selectedOption)
            val selectedLabel = selected?.substringBefore('\t')
            val forceSoftcoreLocked = stringResource(dev.cannoli.ui.R.string.force_softcore_locked)
            val rightBottomItems = when {
                // A Game ID locks the toggle, so its row offers no primary action.
                selectedLabel == MENU_FORCE_SOFTCORE && selected.substringAfter('\t', "") == forceSoftcoreLocked ->
                    emptyList()
                selectedLabel == MENU_FORCE_SOFTCORE ->
                    listOf(buttonStyle.confirm to stringResource(dev.cannoli.ui.R.string.label_toggle))
                else ->
                    listOf(buttonStyle.confirm to stringResource(dev.cannoli.ui.R.string.label_select))
            }
            ListDialogScreen(
                backgroundImagePath = backgroundImagePath,
                backgroundTint = backgroundTint,
                title = dialogState.gameName,
                listFontSize = listFontSize,
                listLineHeight = listLineHeight,
                fullWidth = dialogState.options.any { it.contains('\t') },
                rightBottomItems = rightBottomItems,
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

        is KeyboardHost -> {
            val host = dialogState
            val keyboardTitle = host.titleRes?.let { stringResource(it) }
                ?: (dialogState as? DialogState.RenameInput)?.let { rn ->
                    when (rn.gameName) {
                        "launcher_global_search" -> stringResource(R.string.search_global)
                        "romm_global_search" -> stringResource(R.string.search_romm)
                        "launcher_search", "romm_search", "romm_collection_search" -> rn.searchScope?.let { stringResource(R.string.search_in_platform, it) }
                        "romm_device_name" -> stringResource(dev.cannoli.ui.R.string.dialog_romm_device_name_title)
                        else -> null
                    }
                }
            KeyboardOverlay(
                state = host.keyboard,
                title = keyboardTitle,
                buttonStyle = buttonStyle
            )
        }

        is DialogState.KeyboardHelp -> {
            KeyboardHelpOverlay(
                layout = dialogState.layout,
                titleFontSize = listFontSize,
                titleLineHeight = listLineHeight,
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

        is DialogState.RALoggingIn -> {
            RALoggingInOverlay(message = dialogState.message, failed = dialogState.failed, buttonStyle = buttonStyle)
        }

        is DialogState.RAPreloadProgress -> {
            RALoggingInOverlay(
                message = stringResource(R.string.achievos_preload_progress, dialogState.gameName),
                buttonStyle = buttonStyle,
            )
        }
        is DialogState.RAPreloadResult -> {
            MessageOverlay(
                message = dialogState.message,
                buttonStyle = buttonStyle,
                buttonLabel = stringResource(R.string.label_back),
            )
        }

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

        is DialogState.LibrarySwitchConfirm -> {
            ConfirmOverlay(
                message = stringResource(R.string.library_switch_confirm),
                buttonStyle = buttonStyle,
                confirmLabel = stringResource(R.string.label_proceed),
            )
        }

        is DialogState.IntentAuditResult -> {
            RestartOverlay(message = dialogState.message, buttonStyle = buttonStyle)
        }

        is DialogState.SystemFoldersRegenerated -> {
            RestartOverlay(message = dialogState.message, buttonStyle = buttonStyle)
        }

        is DialogState.QuickMenu -> {
            val conflictCount = dialogState.conflictCount
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
                    val label = when {
                        row == dev.cannoli.scorza.ui.quickmenu.QuickMenuRow.CONFLICTS && conflictCount > 0 ->
                            pluralStringResource(dev.cannoli.ui.R.plurals.quick_menu_conflicts, conflictCount, conflictCount)
                        row == dev.cannoli.scorza.ui.quickmenu.QuickMenuRow.ERRORS && dialogState.syncErrorCount > 0 ->
                            pluralStringResource(dev.cannoli.ui.R.plurals.quick_menu_sync_errors, dialogState.syncErrorCount, dialogState.syncErrorCount)
                        else -> quickMenuLabel(row)
                    }
                    PillRowText(
                        label = label,
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
                endpoints = dialogState.endpoints,
                kitchenRunning = dialogState.kitchenRunning,
                pin = dialogState.pin,
                romm = dialogState.romm,
                selectedIndex = dialogState.selectedIndex,
                buttonStyle = buttonStyle,
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

        is DialogState.RommSaveSyncMenu -> {
            val rows = RommSaveSyncRow.visibleRows(dialogState.supported, dialogState.enabled, dialogState.pendingConflicts, dialogState.syncErrors, dialogState.hasBackups)
            val selectedRow = rows.getOrNull(dialogState.selectedIndex)
            val isCycleRow = selectedRow == RommSaveSyncRow.TOGGLE || selectedRow == RommSaveSyncRow.BACKUPS
            ListDialogScreen(
                backgroundImagePath = backgroundImagePath,
                backgroundTint = backgroundTint,
                title = stringResource(R.string.setting_romm_save_sync),
                listFontSize = listFontSize,
                listLineHeight = listLineHeight,
                leftBottomItems = if (isCycleRow)
                    listOf(DPAD_HORIZONTAL to stringResource(R.string.label_change)) else emptyList(),
                rightBottomItems = listOf(buttonStyle.confirm to stringResource(R.string.label_select)),
                buttonStyle = buttonStyle
            ) {
                List(items = rows, selectedIndex = dialogState.selectedIndex, itemHeight = itemHeight) { _, row, isSelected ->
                    when (row) {
                        RommSaveSyncRow.TOGGLE -> PillRowKeyValue(
                            label = stringResource(R.string.setting_romm_save_sync),
                            value = stringResource(
                                if (!dialogState.supported) R.string.romm_save_sync_needs_500
                                else if (dialogState.enabled) R.string.value_on else R.string.value_off
                            ),
                            isSelected = isSelected,
                            fontSize = listFontSize,
                            lineHeight = listLineHeight,
                            verticalPadding = listVerticalPadding
                        )
                        RommSaveSyncRow.BACKUPS -> PillRowKeyValue(
                            label = stringResource(R.string.setting_romm_save_backups),
                            value = if (dialogState.backupCount <= 0) stringResource(R.string.value_off)
                            else dialogState.backupCount.toString(),
                            isSelected = isSelected,
                            fontSize = listFontSize,
                            lineHeight = listLineHeight,
                            verticalPadding = listVerticalPadding
                        )
                        RommSaveSyncRow.HISTORY -> PillRowText(
                            label = stringResource(R.string.sync_history_title),
                            isSelected = isSelected,
                            fontSize = listFontSize,
                            lineHeight = listLineHeight,
                            verticalPadding = listVerticalPadding
                        )
                        RommSaveSyncRow.CONFLICTS -> PillRowText(
                            label = pluralStringResource(dev.cannoli.ui.R.plurals.quick_menu_conflicts, dialogState.pendingConflicts, dialogState.pendingConflicts),
                            isSelected = isSelected,
                            fontSize = listFontSize,
                            lineHeight = listLineHeight,
                            verticalPadding = listVerticalPadding
                        )
                        RommSaveSyncRow.ERRORS -> PillRowText(
                            label = pluralStringResource(dev.cannoli.ui.R.plurals.quick_menu_sync_errors, dialogState.syncErrors, dialogState.syncErrors),
                            isSelected = isSelected,
                            fontSize = listFontSize,
                            lineHeight = listLineHeight,
                            verticalPadding = listVerticalPadding
                        )
                        RommSaveSyncRow.RESTORE -> PillRowText(
                            label = stringResource(dev.cannoli.ui.R.string.save_backup_restore),
                            isSelected = isSelected,
                            fontSize = listFontSize,
                            lineHeight = listLineHeight,
                            verticalPadding = listVerticalPadding
                        )
                    }
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

        is DialogState.SaveSyncConflict -> {
            val unknown = stringResource(android.R.string.unknownName)
            val localLabel = dialogState.conflict.localTime ?: unknown
            val serverLabel = dialogState.conflict.serverTime ?: unknown
            val options = listOf(
                stringResource(R.string.save_conflict_keep_local) to localLabel,
                stringResource(R.string.save_conflict_use_server) to serverLabel,
            )
            ListDialogScreen(
                backgroundImagePath = backgroundImagePath,
                backgroundTint = backgroundTint,
                title = stringResource(R.string.save_conflict_title),
                listFontSize = listFontSize,
                listLineHeight = listLineHeight,
                rightBottomItems = listOf(buttonStyle.confirm to stringResource(R.string.label_select)),
                buttonStyle = buttonStyle,
            ) {
                androidx.compose.foundation.layout.Column {
                    androidx.compose.material3.Text(
                        text = dialogState.conflict.base,
                        color = LocalCannoliColors.current.text,
                        fontSize = listFontSize,
                        lineHeight = listLineHeight,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                    androidx.compose.material3.Text(
                        text = stringResource(R.string.save_conflict_subtitle),
                        color = LocalCannoliColors.current.text.copy(alpha = 0.55f),
                        fontSize = listFontSize * 0.8f,
                        lineHeight = listLineHeight * 0.8f,
                    )
                    Spacer(modifier = Modifier.height(Spacing.Sm))
                    List(
                        items = options,
                        selectedIndex = dialogState.selectedIndex,
                        itemHeight = itemHeight,
                    ) { _, option, isSelected ->
                        PillRowKeyValue(
                            label = option.first,
                            value = option.second,
                            isSelected = isSelected,
                            fontSize = listFontSize,
                            lineHeight = listLineHeight,
                            verticalPadding = listVerticalPadding,
                        )
                    }
                }
            }
        }

        is DialogState.SaveSyncStaleBlock -> {
            val options = listOf(
                stringResource(R.string.save_stale_play_local),
                stringResource(R.string.label_back),
            )
            ListDialogScreen(
                backgroundImagePath = backgroundImagePath,
                backgroundTint = backgroundTint,
                title = stringResource(R.string.save_stale_title),
                listFontSize = listFontSize,
                listLineHeight = listLineHeight,
                rightBottomItems = listOf(buttonStyle.confirm to stringResource(R.string.label_select)),
                buttonStyle = buttonStyle,
            ) {
                List(
                    items = options,
                    selectedIndex = dialogState.selectedIndex,
                    itemHeight = itemHeight,
                ) { _, option, isSelected ->
                    PillRowText(
                        label = option,
                        isSelected = isSelected,
                        fontSize = listFontSize,
                        lineHeight = listLineHeight,
                        verticalPadding = listVerticalPadding,
                    )
                }
            }
        }

        is DialogState.SyncHistory -> {
            val colors = LocalCannoliColors.current
            val font = LocalCannoliFont.current
            ListDialogScreen(
                backgroundImagePath = backgroundImagePath,
                backgroundTint = backgroundTint,
                title = stringResource(R.string.sync_history_title),
                listFontSize = listFontSize,
                listLineHeight = listLineHeight,
                rightBottomItems = emptyList(),
                buttonStyle = buttonStyle
            ) {
                if (dialogState.entries.isEmpty()) {
                    Text(
                        text = stringResource(R.string.sync_history_empty),
                        color = colors.text.copy(alpha = 0.5f),
                        fontFamily = font,
                        fontSize = listFontSize,
                    )
                } else {
                    List(
                        items = dialogState.entries,
                        selectedIndex = dialogState.selectedIndex,
                        itemHeight = itemHeight,
                    ) { _, row, isSelected ->
                        SyncHistoryRowItem(row, isSelected, listFontSize, listLineHeight, listVerticalPadding)
                    }
                }
            }
        }

        is DialogState.SyncErrors -> {
            val colors = LocalCannoliColors.current
            val font = LocalCannoliFont.current
            ListDialogScreen(
                backgroundImagePath = backgroundImagePath,
                backgroundTint = backgroundTint,
                title = stringResource(dev.cannoli.ui.R.string.sync_errors_title),
                listFontSize = listFontSize,
                listLineHeight = listLineHeight,
                rightBottomItems = emptyList(),
                buttonStyle = buttonStyle
            ) {
                if (dialogState.errors.isEmpty()) {
                    Text(
                        text = stringResource(dev.cannoli.ui.R.string.sync_errors_empty),
                        color = colors.text.copy(alpha = 0.5f),
                        fontFamily = font,
                        fontSize = listFontSize,
                    )
                } else {
                    List(
                        items = dialogState.errors,
                        selectedIndex = dialogState.selectedIndex,
                        itemHeight = itemHeight,
                    ) { _, err, isSelected ->
                        SyncErrorRowItem(err, isSelected, listFontSize, listLineHeight, listVerticalPadding)
                    }
                }
            }
        }

        is DialogState.RommSavesMenu -> {
            ListDialogScreen(
                backgroundImagePath = backgroundImagePath,
                backgroundTint = backgroundTint,
                title = dialogState.title,
                listFontSize = listFontSize,
                listLineHeight = listLineHeight,
                rightBottomItems = listOf(buttonStyle.confirm to stringResource(R.string.label_select)),
                buttonStyle = buttonStyle
            ) {
                List(items = dialogState.options, selectedIndex = dialogState.selectedIndex, itemHeight = itemHeight) { _, option, isSelected ->
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

        is DialogState.SaveBackupGames -> {
            val colors = LocalCannoliColors.current
            val font = LocalCannoliFont.current
            ListDialogScreen(
                backgroundImagePath = backgroundImagePath,
                backgroundTint = backgroundTint,
                title = stringResource(dev.cannoli.ui.R.string.save_backup_games_title),
                listFontSize = listFontSize,
                listLineHeight = listLineHeight,
                rightBottomItems = listOf(buttonStyle.confirm to stringResource(R.string.label_select)),
                buttonStyle = buttonStyle
            ) {
                if (dialogState.games.isEmpty()) {
                    Text(text = stringResource(dev.cannoli.ui.R.string.save_backup_none), color = colors.text.copy(alpha = 0.5f), fontFamily = font, fontSize = listFontSize)
                } else {
                    List(items = dialogState.games, selectedIndex = dialogState.selectedIndex, itemHeight = itemHeight) { _, game, isSelected ->
                        PillRowKeyValue(
                            label = game.displayName,
                            value = game.tag.uppercase(),
                            isSelected = isSelected,
                            fontSize = listFontSize,
                            lineHeight = listLineHeight,
                            verticalPadding = listVerticalPadding,
                        )
                    }
                }
            }
        }

        is DialogState.SaveBackupList -> {
            val colors = LocalCannoliColors.current
            val font = LocalCannoliFont.current
            ListDialogScreen(
                backgroundImagePath = backgroundImagePath,
                backgroundTint = backgroundTint,
                title = dialogState.displayName,
                listFontSize = listFontSize,
                listLineHeight = listLineHeight,
                rightBottomItems = listOf(buttonStyle.confirm to stringResource(R.string.label_select)),
                buttonStyle = buttonStyle
            ) {
                if (dialogState.backups.isEmpty()) {
                    Text(text = stringResource(dev.cannoli.ui.R.string.save_backup_none), color = colors.text.copy(alpha = 0.5f), fontFamily = font, fontSize = listFontSize)
                } else {
                    List(items = dialogState.backups, selectedIndex = dialogState.selectedIndex, itemHeight = itemHeight) { _, backup, isSelected ->
                        PillRowKeyValue(
                            label = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(backup.stamp)),
                            value = dev.cannoli.scorza.ui.screens.RommGameDetailLayout.formatBytes(backup.sizeBytes),
                            isSelected = isSelected,
                            fontSize = listFontSize,
                            lineHeight = listLineHeight,
                            verticalPadding = listVerticalPadding,
                        )
                    }
                }
            }
        }

        is DialogState.SaveBackupRestoreConfirm -> {
            ConfirmOverlay(
                message = "${stringResource(dev.cannoli.ui.R.string.save_backup_restore_confirm)}\n${dialogState.dateLabel}",
                buttonStyle = buttonStyle,
            )
        }

        is DialogState.ConflictsMenu -> {
            ListDialogScreen(
                backgroundImagePath = backgroundImagePath,
                backgroundTint = backgroundTint,
                title = stringResource(dev.cannoli.ui.R.string.conflicts_title),
                listFontSize = listFontSize,
                listLineHeight = listLineHeight,
                leftBottomItems = listOf(DPAD_HORIZONTAL to stringResource(R.string.label_change)),
                rightBottomItems = listOf(START_GLYPH to stringResource(R.string.label_confirm)),
                buttonStyle = buttonStyle,
            ) {
                List(
                    items = dialogState.rows,
                    selectedIndex = dialogState.selectedIndex,
                ) { _, row, isSelected ->
                    ConflictRowItem(
                        row = row,
                        choiceLabel = conflictChoiceLabel(row.choice),
                        isSelected = isSelected,
                        fontSize = listFontSize,
                        lineHeight = listLineHeight,
                        verticalPadding = listVerticalPadding,
                    )
                }
            }
        }

        is DialogState.ConflictsApplying -> {
            OverlayScrim {
                androidx.compose.material3.Text(
                    text = stringResource(R.string.conflicts_applying),
                    color = LocalCannoliColors.current.text,
                    fontSize = listFontSize,
                    lineHeight = listLineHeight,
                )
            }
        }

        is DialogState.MissingCore -> LaunchIssue(
            title = stringResource(R.string.launch_issue_core_missing),
            subject = stringResource(R.string.launch_issue_subject, dialogState.platformName, dialogState.coreName),
            confirmLabel = stringResource(R.string.label_change_emulator).takeIf { dialogState.platformTag != null },
            buttonStyle = buttonStyle,
        )

        is DialogState.UnsupportedContent -> LaunchIssue(
            title = stringResource(R.string.launch_issue_unsupported, ".${dialogState.extension}"),
            // A third line says what the core does read, so the answer to "then what do I need" is
            // on the screen rather than left to the picker. Trimmed by line length rather than by
            // a count, because formats are short: six of them still fit, while the wide computer
            // cores that declare twenty would not. Those are also the cores this gate almost never
            // fires for, since they read nearly everything. The order is the core's own, which
            // leads with the format it is actually for.
            subject = buildList {
                val all = dialogState.supported.map { ".$it" }
                // The pairing line carries the label, so the formats below it read as the answer to
                // the title rather than as a loose list.
                add(stringResource(
                    if (all.isEmpty()) R.string.launch_issue_subject else R.string.launch_issue_supports,
                    dialogState.platformName,
                    dialogState.coreName,
                ))
                if (all.isNotEmpty()) {
                    val shown = formatsThatFit(all, FORMAT_LINE_CHARS)
                    val rest = all.size - shown
                    val head = all.take(shown).joinToString(", ")
                    add(
                        if (rest > 0) {
                            head + " " + pluralStringResource(
                                R.plurals.launch_issue_more_formats, rest, rest
                            )
                        } else head
                    )
                }
            }.joinToString("\n"),
            confirmLabel = stringResource(R.string.label_change_emulator)
                .takeIf { dialogState.platformTag != null },
            buttonStyle = buttonStyle,
        )

        is DialogState.MissingApp -> LaunchIssue(
            title = stringResource(R.string.launch_issue_app_missing),
            subject = stringResource(R.string.launch_issue_subject, dialogState.platformName, dialogState.appName),
            confirmLabel = when {
                appListPlatformTag == "tools" || appListPlatformTag == "ports" ->
                    stringResource(R.string.label_remove)
                dialogState.platformTag != null -> stringResource(R.string.label_change_emulator)
                else -> null
            },
            buttonStyle = buttonStyle,
        )

        is DialogState.NoEmulatorSet -> LaunchIssue(
            title = stringResource(R.string.launch_issue_no_emulator),
            subject = dialogState.platformName,
            confirmLabel = stringResource(R.string.label_change_emulator).takeIf { dialogState.platformTag != null },
            buttonStyle = buttonStyle,
        )

        is DialogState.MissingBios -> LaunchIssue(
            title = stringResource(R.string.launch_issue_bios_missing),
            // The only case whose subject is a list, so the files get a line each rather than a
            // run-on that wraps mid-filename. Capped because the screen does not scroll: past the
            // cap the count stands in, and the BIOS screen behind the remedy has the full set.
            subject = buildList {
                add(dialogState.platformName)
                val shown = if (dialogState.files.size > BIOS_FILES_SHOWN) BIOS_FILES_SHOWN - 1
                            else dialogState.files.size
                addAll(dialogState.files.take(shown))
                val rest = dialogState.files.size - shown
                if (rest > 0) add(pluralStringResource(R.plurals.launch_issue_more_files, rest, rest))
            }.joinToString("\n"),
            confirmLabel = stringResource(R.string.label_view_bios).takeIf { dialogState.platformTag != null },
            buttonStyle = buttonStyle,
        )

        is DialogState.LaunchError -> LaunchIssue(
            title = stringResource(R.string.dialog_title_launch_error),
            subject = dialogState.message,
            buttonStyle = buttonStyle,
        )

        is DialogState.Launching -> dev.cannoli.ui.components.LaunchScrim()

        is DialogState.DeleteConfirm -> ConfirmOverlay(
            message = stringResource(R.string.dialog_delete_confirm, dialogState.gameName),
            buttonStyle = buttonStyle,
            confirmLabel = stringResource(R.string.label_delete),
        )

        is DialogState.DeleteCollectionConfirm -> ConfirmOverlay(
            message = stringResource(R.string.dialog_delete_confirm, dialogState.displayName),
            buttonStyle = buttonStyle,
            confirmLabel = stringResource(R.string.label_delete),
        )

        is DialogState.QuitConfirm -> ConfirmOverlay(
            message = stringResource(R.string.dialog_quit_confirm),
            buttonStyle = buttonStyle,
            confirmLabel = stringResource(R.string.label_quit),
        )

        is DialogState.UninstallCoreConfirm -> ConfirmOverlay(
            message = stringResource(
                R.string.dialog_uninstall_core_confirm,
                dialogState.coreName,
                android.text.format.Formatter.formatShortFileSize(
                    androidx.compose.ui.platform.LocalContext.current, dialogState.bytes
                ),
            ),
            buttonStyle = buttonStyle,
            confirmLabel = stringResource(R.string.label_uninstall),
        )

        is DialogState.RemoveUnusedCoresConfirm -> ConfirmOverlay(
            message = pluralStringResource(
                R.plurals.dialog_remove_unused_cores_confirm,
                dialogState.cores,
                dialogState.cores,
                android.text.format.Formatter.formatShortFileSize(
                    androidx.compose.ui.platform.LocalContext.current, dialogState.bytes
                ),
            ),
            buttonStyle = buttonStyle,
            confirmLabel = stringResource(R.string.label_remove_unused),
        )

        is DialogState.CheckingCores -> dev.cannoli.ui.components.ProgressOverlay(
            title = stringResource(R.string.osd_checking_cores),
            subtitle = "",
            progress = null,
            error = null,
            buttonStyle = buttonStyle,
        )

        is DialogState.UpdateCoresConfirm -> ConfirmOverlay(
            message = pluralStringResource(
                R.plurals.dialog_update_cores_confirm,
                dialogState.cores,
                dialogState.cores,
                android.text.format.Formatter.formatShortFileSize(
                    androidx.compose.ui.platform.LocalContext.current, dialogState.bytes
                ),
            ),
            buttonStyle = buttonStyle,
            confirmLabel = stringResource(R.string.label_update),
        )

        // One standing statement rather than the core name: twenty-seven names in five seconds
        // reads as flicker, and which core is in flight is not something to act on.
        is DialogState.UpdatingCores -> dev.cannoli.ui.components.ProgressOverlay(
            title = stringResource(R.string.updating_cores),
            subtitle = "",
            progress = coreUpdate?.fraction,
            error = null,
            buttonStyle = buttonStyle,
        )

        is DialogState.RetroAchievementsLogoutConfirm -> ConfirmOverlay(
            message = stringResource(R.string.achievos_logout_confirm),
            buttonStyle = buttonStyle,
            confirmLabel = stringResource(R.string.label_logout),
        )

        is DialogState.PlatformResetConfirm -> ConfirmOverlay(
            message = stringResource(R.string.dialog_reset_platform_confirm, dialogState.platformName),
            buttonStyle = buttonStyle,
            confirmLabel = stringResource(R.string.label_reset),
        )

        is DialogState.ResetCustomConfigConfirm -> ConfirmOverlay(
            message = stringResource(R.string.dialog_reset_custom_config_confirm),
            buttonStyle = buttonStyle,
            confirmLabel = stringResource(R.string.label_reset),
        )

        is DialogState.PermissionDetail -> PermissionDetailOverlay(
            title = stringResource(dialogState.permission.labelRes),
            explanation = stringResource(dialogState.permission.explanationRes),
            actionable = dialogState.permission.settingsAction != null,
            buttonStyle = buttonStyle,
        )

        is DialogState.RenameResult -> MessageOverlay(
            message = if (dialogState.success) {
                stringResource(R.string.dialog_rename_success)
            } else {
                stringResource(R.string.dialog_rename_failed, dialogState.message)
            },
            buttonStyle = buttonStyle,
        )

        is DialogState.CollectionCreated -> MessageOverlay(
            message = stringResource(R.string.collection_created, dialogState.collectionName),
            buttonStyle = buttonStyle,
        )

        is DialogState.SaveSyncChecking ->
            dev.cannoli.ui.components.LaunchScrim(status = stringResource(R.string.save_sync_checking))

        else -> {}
    }
}

@Composable
private fun SyncHistoryRowItem(row: SyncHistoryRow, isSelected: Boolean, fontSize: TextUnit, lineHeight: TextUnit, verticalPadding: Dp) {
    val glyph = when (row.direction) {
        SyncDirection.UPLOAD -> CannoliIcons.SyncUpload.glyph
        SyncDirection.DOWNLOAD -> CannoliIcons.SyncDownload.glyph
        SyncDirection.CONFLICT, SyncDirection.ERROR -> CannoliIcons.SyncAlert.glyph
    }
    PillRowKeyValue(
        label = row.name,
        value = if (row.detail != null) "${row.detail}  ${row.relativeTime}" else row.relativeTime,
        isSelected = isSelected,
        fontSize = fontSize,
        lineHeight = lineHeight,
        verticalPadding = verticalPadding,
        leadingIcon = glyph,
    )
}

@Composable
private fun SyncErrorRowItem(err: dev.cannoli.scorza.romm.sync.SyncFailure, isSelected: Boolean, fontSize: TextUnit, lineHeight: TextUnit, verticalPadding: Dp) {
    PillRowKeyValue(
        label = err.displayName,
        value = err.reason,
        isSelected = isSelected,
        fontSize = fontSize,
        lineHeight = lineHeight,
        verticalPadding = verticalPadding,
    )
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

@Composable
private fun quickMenuLabel(row: dev.cannoli.scorza.ui.quickmenu.QuickMenuRow): String = when (row) {
    dev.cannoli.scorza.ui.quickmenu.QuickMenuRow.SETTINGS -> stringResource(R.string.settings_title)
    dev.cannoli.scorza.ui.quickmenu.QuickMenuRow.ROMM -> stringResource(R.string.quick_menu_romm)
    dev.cannoli.scorza.ui.quickmenu.QuickMenuRow.DOWNLOADS -> stringResource(R.string.quick_menu_download_queue)
    dev.cannoli.scorza.ui.quickmenu.QuickMenuRow.SYNC_HISTORY -> stringResource(R.string.quick_menu_sync_history)
    dev.cannoli.scorza.ui.quickmenu.QuickMenuRow.CONFLICTS -> stringResource(dev.cannoli.ui.R.string.conflicts_title)
    dev.cannoli.scorza.ui.quickmenu.QuickMenuRow.ERRORS -> stringResource(dev.cannoli.ui.R.string.sync_errors_title)
    dev.cannoli.scorza.ui.quickmenu.QuickMenuRow.KITCHEN -> stringResource(R.string.quick_menu_kitchen)
    dev.cannoli.scorza.ui.quickmenu.QuickMenuRow.RESCAN -> stringResource(R.string.quick_menu_rescan)
    dev.cannoli.scorza.ui.quickmenu.QuickMenuRow.INFO -> stringResource(R.string.quick_menu_info)
    dev.cannoli.scorza.ui.quickmenu.QuickMenuRow.ABOUT -> stringResource(R.string.settings_about)
    dev.cannoli.scorza.ui.quickmenu.QuickMenuRow.DEBUG -> stringResource(R.string.settings_debug)
}

@Composable
private fun conflictChoiceLabel(choice: ConflictChoice): String = when (choice) {
    ConflictChoice.KEEP_LOCAL -> stringResource(dev.cannoli.ui.R.string.conflict_keep_local)
    ConflictChoice.USE_SERVER -> stringResource(dev.cannoli.ui.R.string.conflict_use_server)
    ConflictChoice.SKIP -> stringResource(dev.cannoli.ui.R.string.conflict_skip)
}

@Composable
private fun ConflictRowItem(
    row: dev.cannoli.scorza.ui.screens.ConflictRow,
    choiceLabel: String,
    isSelected: Boolean,
    fontSize: TextUnit,
    lineHeight: TextUnit,
    verticalPadding: Dp,
) {
    val colors = LocalCannoliColors.current
    val textColor = if (isSelected) colors.highlightText else colors.text
    val local = row.localMillis
    val server = row.serverMillis
    val localOlder = local != null && server != null && local < server
    val serverOlder = local != null && server != null && server < local
    val sub = fontSize * 0.72f
    val highlight = if (isSelected) {
        Modifier.clip(RoundedCornerShape(12.dp)).background(colors.highlight)
    } else {
        Modifier
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .then(highlight)
            .padding(horizontal = 14.dp, vertical = verticalPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.name,
                color = textColor,
                fontSize = fontSize,
                lineHeight = lineHeight,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
            ConflictTimeLine(stringResource(dev.cannoli.ui.R.string.conflict_yours), local, localOlder, sub, textColor)
            ConflictTimeLine(stringResource(dev.cannoli.ui.R.string.conflict_server), server, serverOlder, sub, textColor)
        }
        Spacer(modifier = Modifier.width(Spacing.Sm))
        Text(text = choiceLabel, color = textColor, fontSize = fontSize, lineHeight = lineHeight, maxLines = 1)
    }
}

@Composable
private fun ConflictTimeLine(
    label: String,
    millis: Long?,
    older: Boolean,
    fontSize: TextUnit,
    color: androidx.compose.ui.graphics.Color,
) {
    val suffix = if (older) "  · " + stringResource(dev.cannoli.ui.R.string.conflict_older) else ""
    Row(modifier = Modifier.fillMaxWidth().padding(top = 1.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text = label, color = color, fontSize = fontSize, lineHeight = fontSize * 1.1f, maxLines = 1, modifier = Modifier.width(58.dp))
        Text(
            text = formatConflictTime(millis) + suffix,
            color = color,
            fontSize = fontSize,
            lineHeight = fontSize * 1.1f,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
    }
}

private fun formatConflictTime(millis: Long?): String =
    millis?.let {
        java.text.SimpleDateFormat("MMM d, h:mm a", java.util.Locale.getDefault()).format(java.util.Date(it))
    } ?: "—"

val ROMM_ADVANCED_ROWS = listOf(R.string.romm_settings_rebuild, R.string.romm_settings_download_art)

enum class RommActionRow(@androidx.annotation.StringRes val labelRes: Int) {
    DOWNLOADS(R.string.romm_download_queue),
    ;
    companion object {
        fun visibleRows(hasDownloads: Boolean): List<RommActionRow> =
            if (hasDownloads) entries else entries.filterNot { it == DOWNLOADS }
    }
}

enum class RaAccountRow(@androidx.annotation.StringRes val labelRes: Int, val isCycle: Boolean = false) {
    ACCOUNT(R.string.achievos_account_row_account),
    HARDCORE(R.string.achievos_account_row_hardcore, isCycle = true),
    OFFLINE_SETS(R.string.achievos_account_row_offline_sets),
}

enum class RommSettingsRow(@androidx.annotation.StringRes val labelRes: Int, val isCycle: Boolean = false) {
    COVER_ART(R.string.romm_settings_cover_art, isCycle = true),
    CONCURRENT(R.string.romm_settings_concurrent, isCycle = true),
    SAVE_SYNC(R.string.setting_romm_save_sync),
    PLATFORMS(R.string.romm_settings_platforms),
    COLLECTIONS(R.string.romm_settings_collections),
    ADVANCED(R.string.romm_settings_advanced),
    SERVER_INFO(R.string.romm_settings_server_info),
}

enum class RommSaveSyncRow {
    TOGGLE, BACKUPS, HISTORY, CONFLICTS, ERRORS, RESTORE;
    companion object {
        fun visibleRows(supported: Boolean, enabled: Boolean, pendingConflicts: Int = 0, syncErrors: Int = 0, hasBackups: Boolean = false): List<RommSaveSyncRow> =
            buildList {
                add(TOGGLE)
                if (supported && enabled) {
                    add(BACKUPS)
                    add(HISTORY)
                    if (pendingConflicts > 0) add(CONFLICTS)
                    if (syncErrors > 0) add(ERRORS)
                }
                // Restore is a recovery action, so it stays available even when sync is off.
                if (hasBackups) add(RESTORE)
            }
    }
}

@androidx.annotation.StringRes
fun raTokenStatusRes(state: RaTokenState): Int = when (state) {
    RaTokenState.CHECKING -> R.string.achievos_token_checking
    RaTokenState.VALID -> R.string.achievos_token_valid
    RaTokenState.INVALID -> R.string.achievos_token_invalid
    RaTokenState.UNREACHABLE -> R.string.achievos_token_offline
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
private fun PermissionDetailOverlay(
    title: String,
    explanation: String,
    actionable: Boolean,
    buttonStyle: ButtonStyle,
) {
    val colors = LocalCannoliColors.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(androidx.compose.ui.graphics.Color.Black),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = title,
                color = colors.title,
                fontFamily = LocalCannoliFont.current,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = explanation,
                color = colors.text,
                fontFamily = LocalCannoliFont.current,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
        BottomBar(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(screenPadding),
            leftItems = listOf(buttonStyle.back to stringResource(R.string.label_back)),
            rightItems = if (actionable) {
                listOf(buttonStyle.confirm to stringResource(R.string.label_open_settings))
            } else {
                emptyList()
            }
        )
    }
}

@Composable
private fun ConfirmOverlay(
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

// Four filenames plus the platform is the tallest subject the screen holds at the largest text size
// on a 320dp handheld. Amiga's four Kickstart ROMs are the worst real case, so nothing is truncated
// today; the cap is a guard for a core that declares more.
private const val BIOS_FILES_SHOWN = 4

// Roughly one line of the subject at its usual size. The screen shrinks a long subject rather
// than clipping it, so this only decides where a list stops being worth reading.
private const val FORMAT_LINE_CHARS = 40

/**
 * How many formats to name before "and N more" is the better use of the room. Returns every one of
 * them when they all fit, so a short list is never truncated just for having several entries.
 */
private fun formatsThatFit(all: List<String>, budget: Int): Int {
    if (all.joinToString(", ").length <= budget) return all.size
    // Room kept for the longest " and NN more" this can append.
    val reserved = 12
    for (n in all.size - 1 downTo 2) {
        if (all.take(n).joinToString(", ").length + reserved <= budget) return n
    }
    return 1
}
