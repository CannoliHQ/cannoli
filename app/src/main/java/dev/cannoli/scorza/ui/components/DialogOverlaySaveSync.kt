package dev.cannoli.scorza.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import dev.cannoli.scorza.romm.sync.SyncDirection
import dev.cannoli.scorza.ui.screens.SyncHistoryRow
import dev.cannoli.scorza.ui.screens.ConflictChoice
import dev.cannoli.scorza.ui.screens.DialogState
import dev.cannoli.ui.ButtonStyle
import dev.cannoli.ui.DPAD_HORIZONTAL
import dev.cannoli.ui.START_GLYPH
import dev.cannoli.ui.theme.CannoliIcons
import dev.cannoli.ui.theme.LocalCannoliColors
import dev.cannoli.ui.theme.LocalCannoliFont
import dev.cannoli.ui.components.List
import dev.cannoli.ui.components.PillRowKeyValue
import dev.cannoli.ui.components.PillRowText
import dev.cannoli.ui.components.OverlayScrim
import dev.cannoli.ui.theme.Spacing

@Composable
internal fun SaveSyncDialogs(
    dialogState: DialogState,
    backgroundImagePath: String?,
    backgroundTint: Int,
    listFontSize: TextUnit,
    listLineHeight: TextUnit,
    listVerticalPadding: Dp,
    buttonStyle: ButtonStyle,
    itemHeight: Dp,
) {
    when (dialogState) {
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

