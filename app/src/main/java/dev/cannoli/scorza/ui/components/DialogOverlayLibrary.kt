package dev.cannoli.scorza.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import dev.cannoli.scorza.R
import dev.cannoli.scorza.input.MENU_FORCE_SOFTCORE
import dev.cannoli.scorza.model.VirtualPlatformTags
import dev.cannoli.scorza.ui.screens.DialogState
import dev.cannoli.scorza.ui.screens.KeyboardHost
import dev.cannoli.scorza.ui.screens.RenameTarget
import dev.cannoli.ui.ButtonStyle
import dev.cannoli.ui.components.ColorPickerOverlay
import dev.cannoli.ui.components.HexColorInputOverlay
import dev.cannoli.igm.guideHelpGroups
import dev.cannoli.ui.components.HelpOverlay
import dev.cannoli.ui.components.KeyboardHelpOverlay
import dev.cannoli.ui.components.KeyboardOverlay
import dev.cannoli.ui.components.LaunchIssue
import dev.cannoli.ui.components.List
import dev.cannoli.ui.components.PillRowKeyValue
import dev.cannoli.ui.components.PillRowText
import dev.cannoli.ui.components.MessageOverlay

@Composable
internal fun LibraryDialogs(
    dialogState: DialogState,
    backgroundImagePath: String?,
    backgroundTint: Int,
    listFontSize: TextUnit,
    listLineHeight: TextUnit,
    listVerticalPadding: Dp,
    buttonStyle: ButtonStyle,
    appListPlatformTag: String?,
    itemHeight: Dp,
) {
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
                            label = menuOptionLabel(parts[0]),
                            value = parts[1],
                            isSelected = isSelected,
                            fontSize = listFontSize,
                            lineHeight = listLineHeight,
                            verticalPadding = listVerticalPadding
                        )
                    } else {
                        PillRowText(
                            label = menuOptionLabel(option),
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
                        label = menuOptionLabel(option),
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
                    when (val target = rn.target) {
                        is RenameTarget.LauncherGlobalSearch -> stringResource(R.string.search_global)
                        is RenameTarget.RommGlobalSearch -> stringResource(R.string.search_romm)
                        is RenameTarget.ScopedSearch -> stringResource(R.string.search_in_platform, target.scope)
                        is RenameTarget.RommDeviceName -> stringResource(dev.cannoli.ui.R.string.dialog_romm_device_name_title)
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

        is DialogState.GuideHelp -> {
            HelpOverlay(
                titleRes = dev.cannoli.ui.R.string.guide_help_title,
                groups = guideHelpGroups(dialogState.guideType),
                titleFontSize = listFontSize,
                titleLineHeight = listLineHeight,
                buttonStyle = buttonStyle
            )
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
                VirtualPlatformTags.isAppList(appListPlatformTag) ->
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

        else -> {}
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

/**
 * The label for a context menu row. Options carry a stable key so the handlers can compare them, so
 * this is where the key becomes words. Anything with no entry is passed through: menus that build
 * their own rows from already-translated text share this renderer.
 */
@Composable
private fun menuOptionLabel(key: String): String =
    dev.cannoli.scorza.input.MENU_LABELS[key]?.let { stringResource(it) } ?: key
