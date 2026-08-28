package dev.cannoli.scorza.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import dev.cannoli.scorza.R
import dev.cannoli.scorza.ui.screens.DialogState
import dev.cannoli.ui.ButtonStyle
import dev.cannoli.ui.theme.LocalCannoliColors
import dev.cannoli.ui.theme.LocalCannoliFont
import dev.cannoli.ui.components.BottomBar
import dev.cannoli.ui.components.QuickInfoOverlay
import dev.cannoli.ui.components.List
import dev.cannoli.ui.components.PillRowText
import dev.cannoli.ui.components.MessageOverlay
import dev.cannoli.ui.components.RALoggingInOverlay
import dev.cannoli.ui.components.RestartOverlay
import dev.cannoli.ui.components.UpdateDownloadOverlay
import dev.cannoli.ui.components.screenPadding

@Composable
internal fun SystemDialogs(
    dialogState: DialogState,
    backgroundImagePath: String?,
    backgroundTint: Int,
    listFontSize: TextUnit,
    listLineHeight: TextUnit,
    listVerticalPadding: Dp,
    downloadProgress: Float,
    downloadError: String?,
    coreUpdate: dev.cannoli.scorza.launcher.CoreDownloadService.UpdateProgress?,
    updateAvailable: Boolean,
    buttonStyle: ButtonStyle,
    itemHeight: Dp,
) {
    when (dialogState) {
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

        is DialogState.RescanProgress -> {
            dev.cannoli.scorza.ui.screens.HousekeepingScreen(
                kind = dev.cannoli.scorza.ui.screens.HousekeepingKind.LIBRARY_REFRESH,
                progress = dialogState.progress,
                statusLabel = dialogState.label,
            )
        }

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

        is DialogState.UpdateShadersConfirm -> ConfirmOverlay(
            message = stringResource(
                R.string.dialog_update_shaders_confirm,
                android.text.format.Formatter.formatShortFileSize(
                    androidx.compose.ui.platform.LocalContext.current, dialogState.bytes
                ),
                roundedUpSize(dialogState.installedBytes),
            ),
            buttonStyle = buttonStyle,
            confirmLabel = stringResource(R.string.label_update),
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

        else -> {}
    }
}

/**
 * A size to warn someone with, rounded up to a whole unit.
 *
 * The exact figure is a guess built from a file count and a cluster size, so rendering it as
 * "0.96 GB" claims a precision it does not have and reads as smaller than it is. Rounding up keeps
 * the warning honest in the direction that matters, and the threshold sits below a gigabyte because
 * that is the point where "nearly a gigabyte" is what a person would say.
 */
private fun roundedUpSize(bytes: Long): String =
    if (bytes >= 900_000_000L) "${kotlin.math.ceil(bytes / 1_000_000_000.0).toInt()} GB"
    else "${kotlin.math.ceil(bytes / 1_000_000.0).toInt()} MB"

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

