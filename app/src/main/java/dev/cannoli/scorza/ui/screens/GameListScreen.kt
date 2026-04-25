package dev.cannoli.scorza.ui.screens

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cannoli.scorza.R
import dev.cannoli.scorza.model.AppType
import dev.cannoli.scorza.model.ListItem
import dev.cannoli.scorza.settings.ArtScale
import dev.cannoli.scorza.ui.components.DialogOverlay
import dev.cannoli.scorza.ui.viewmodel.GameListViewModel
import dev.cannoli.ui.ButtonStyle
import dev.cannoli.ui.START_GLYPH
import dev.cannoli.ui.STAR
import dev.cannoli.ui.components.BottomBar
import dev.cannoli.ui.components.ConfirmOverlay
import dev.cannoli.ui.components.LaunchErrorDialog
import dev.cannoli.ui.components.List
import dev.cannoli.ui.components.MarqueeEffect
import dev.cannoli.ui.components.MessageOverlay
import dev.cannoli.ui.components.MissingAppDialog
import dev.cannoli.ui.components.MissingCoreDialog
import dev.cannoli.ui.components.PillRow
import dev.cannoli.ui.components.ScreenBackground
import dev.cannoli.ui.components.ScreenTitle
import dev.cannoli.ui.components.footerReservation
import dev.cannoli.ui.components.pillItemHeight
import dev.cannoli.ui.components.screenPadding
import dev.cannoli.ui.theme.LocalCannoliColors
import dev.cannoli.ui.theme.Radius
import dev.cannoli.ui.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private fun ListItem.rowDisplayName(showStar: Boolean): String = when (this) {
    is ListItem.RomItem -> if (showStar) "$STAR ${rom.displayName}" else rom.displayName
    is ListItem.AppItem -> if (showStar) "$STAR ${app.displayName}" else app.displayName
    is ListItem.SubfolderItem -> name
    is ListItem.CollectionItem -> collection.displayName
    is ListItem.ChildCollectionItem -> "/${collection.displayName}"
}

private val ListItem.itemKey: String get() = when (this) {
    is ListItem.RomItem -> "rom:${rom.id}"
    is ListItem.AppItem -> "app:${app.id}"
    is ListItem.SubfolderItem -> "sub:$path"
    is ListItem.CollectionItem -> "coll:${collection.stem}"
    is ListItem.ChildCollectionItem -> "child:${collection.stem}"
}

private val ListItem.isLeafSelectable: Boolean get() = this is ListItem.RomItem || this is ListItem.AppItem

private val ListItem.artPath: String? get() = (this as? ListItem.RomItem)?.rom?.artFile?.absolutePath

private val ListItem.resumeKey: String? get() = (this as? ListItem.RomItem)?.rom?.path?.absolutePath

@Composable
fun GameListScreen(
    viewModel: GameListViewModel,
    backgroundImagePath: String? = null,
    backgroundTint: Int = 0,
    listFontSize: TextUnit = 22.sp,
    listLineHeight: TextUnit = 32.sp,
    listVerticalPadding: Dp = 8.dp,
    dialogState: DialogState = DialogState.None,
    onVisibleRangeChanged: (Int, Int, Boolean) -> Unit = { _, _, _ -> },
    resumableGames: Set<String> = emptySet(),
    swapPlayResume: Boolean = false,
    artWidth: Int = 40,
    artScale: ArtScale = ArtScale.FIT,
    buttonStyle: ButtonStyle = ButtonStyle(),
) {
    val state by viewModel.state.collectAsState()
    val itemHeight = pillItemHeight(listLineHeight, listVerticalPadding)
    val selected = state.items.getOrNull(state.selectedIndex)
    val resumeKey = selected?.resumeKey
    val hasResumeState = resumeKey != null && resumableGames.contains(resumeKey)

    val artPath = selected?.artPath
    val selectedArt by produceState<ImageBitmap?>(null, artPath) {
        value = if (artPath != null) {
            withContext(Dispatchers.IO) {
                try {
                    val opts = BitmapFactory.Options()
                    opts.inJustDecodeBounds = true
                    BitmapFactory.decodeFile(artPath, opts)
                    val maxDim = 1024
                    var sampleSize = 1
                    while (opts.outWidth / sampleSize > maxDim || opts.outHeight / sampleSize > maxDim) sampleSize *= 2
                    opts.inJustDecodeBounds = false
                    opts.inSampleSize = sampleSize
                    BitmapFactory.decodeFile(artPath, opts)?.asImageBitmap()
                } catch (_: Exception) { null }
            }
        } else null
    }

    val showFavoriteStars = viewModel.showFavoriteStars
    val favoriteRomIds = state.favoriteRomIds
    val favoriteAppIds = state.favoriteAppIds

    ScreenBackground(backgroundImagePath = backgroundImagePath, backgroundTint = backgroundTint) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(screenPadding)
        ) {
            val showArt = selectedArt != null && artWidth > 0
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = footerReservation())
            ) {
                ScreenTitle(
                    text = state.breadcrumb,
                    fontSize = listFontSize,
                    lineHeight = listLineHeight,
                )
                Spacer(modifier = Modifier.height(Spacing.Sm))
                if (state.items.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.empty_list, state.breadcrumb),
                            style = MaterialTheme.typography.bodyLarge.copy(
                                fontSize = listFontSize,
                                lineHeight = listLineHeight
                            ),
                            color = LocalCannoliColors.current.text
                        )
                    }
                } else {
                    Row(modifier = Modifier.fillMaxSize()) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .then(if (showArt) Modifier.fillMaxWidth(1f - artWidth / 100f) else Modifier.fillMaxWidth())
                        ) {
                            List(
                                items = state.items,
                                selectedIndex = state.selectedIndex,
                                itemHeight = itemHeight,
                                scrollTarget = state.scrollTarget,
                                reorderMode = state.reorderMode,
                                onVisibleRangeChanged = { first, count, full ->
                                    viewModel.firstVisibleIndex = first
                                    onVisibleRangeChanged(first, count, full)
                                },
                                key = if (state.reorderMode) null else { _, item -> item.itemKey }
                            ) { index, item, isSelected ->
                                val starred = showFavoriteStars && when (item) {
                                    is ListItem.RomItem -> item.rom.id in favoriteRomIds
                                    is ListItem.AppItem -> item.app.id in favoriteAppIds
                                    else -> false
                                }
                                ItemRow(
                                    item = item,
                                    showFavoriteStar = starred,
                                    isSelected = isSelected,
                                    fontSize = listFontSize,
                                    lineHeight = listLineHeight,
                                    verticalPadding = listVerticalPadding,
                                    showReorderIcon = state.reorderMode && isSelected,
                                    checkState = if (state.multiSelectMode && item.isLeafSelectable) index in state.checkedIndices else null
                                )
                            }
                        }
                        if (showArt) {
                            Box(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .fillMaxWidth()
                                    .padding(start = 16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                val art = selectedArt ?: return@Box
                                val artModifier: Modifier
                                val artContentScale: ContentScale
                                when (artScale) {
                                    ArtScale.FIT -> { artModifier = Modifier.fillMaxSize(); artContentScale = ContentScale.Fit }
                                    ArtScale.ORIGINAL -> { artModifier = Modifier.wrapContentSize(); artContentScale = ContentScale.None }
                                    ArtScale.FIT_WIDTH -> { artModifier = Modifier.fillMaxWidth(); artContentScale = ContentScale.FillWidth }
                                    ArtScale.FIT_HEIGHT -> { artModifier = Modifier.fillMaxHeight(); artContentScale = ContentScale.FillHeight }
                                }
                                Image(
                                    bitmap = art,
                                    contentDescription = null,
                                    modifier = artModifier.clip(RoundedCornerShape(Radius.Lg)),
                                    contentScale = artContentScale,
                                    filterQuality = FilterQuality.High
                                )
                            }
                        }
                    }
                }
            }

            val isToolApp = (selected as? ListItem.AppItem)?.app?.type == AppType.TOOL
            val actionLabel = if (state.multiSelectMode) {
                stringResource(R.string.label_toggle)
            } else if (selected is ListItem.SubfolderItem || state.isCollectionsList) {
                stringResource(R.string.label_open)
            } else if (isToolApp) {
                stringResource(R.string.label_launch)
            } else {
                stringResource(R.string.label_play)
            }
            val resumeLabel = stringResource(R.string.label_resume)
            val showNewButton = state.isCollectionsList || state.isCollection
            val leftItems = buildList {
                add(buttonStyle.back to stringResource(R.string.label_back))
                if (showNewButton) add(buttonStyle.west to stringResource(R.string.label_new))
            }
            val rightItems = if (state.items.isEmpty()) {
                emptyList()
            } else if (state.multiSelectMode) {
                listOf(buttonStyle.confirm to actionLabel, START_GLYPH to stringResource(R.string.label_confirm))
            } else if (hasResumeState && swapPlayResume) {
                listOf(buttonStyle.north to actionLabel, buttonStyle.confirm to resumeLabel)
            } else {
                buildList {
                    if (hasResumeState) add(buttonStyle.north to resumeLabel)
                    add(buttonStyle.confirm to actionLabel)
                }
            }
            BottomBar(
                modifier = Modifier.align(Alignment.BottomCenter),
                leftItems = leftItems,
                rightItems = rightItems
            )

            when (dialogState) {
                is DialogState.RenameResult -> MessageOverlay(
                    message = if (dialogState.success) {
                        stringResource(R.string.dialog_rename_success)
                    } else {
                        stringResource(R.string.dialog_rename_failed, dialogState.message)
                    }
                )
                is DialogState.CollectionCreated -> MessageOverlay(
                    message = stringResource(R.string.collection_created, dialogState.collectionName)
                )
                else -> {}
            }
        }
    }

    when (dialogState) {
        is DialogState.MissingCore -> MissingCoreDialog(dialogState.coreName)
        is DialogState.MissingApp -> MissingAppDialog(
            appName = dialogState.appName,
            showRemove = state.platformTag == "tools" || state.platformTag == "ports"
        )
        is DialogState.LaunchError -> LaunchErrorDialog(dialogState.message)
        is DialogState.DeleteConfirm -> ConfirmOverlay(
            message = stringResource(R.string.dialog_delete_confirm, dialogState.gameName)
        )
        is DialogState.DeleteCollectionConfirm -> ConfirmOverlay(
            message = stringResource(R.string.dialog_delete_confirm, dev.cannoli.scorza.model.Collection.stemToDisplayName(dialogState.collectionStem))
        )
        else -> {}
    }

    if (dialogState.isFullScreen) {
        DialogOverlay(
            dialogState = dialogState,
            backgroundImagePath = backgroundImagePath,
            backgroundTint = backgroundTint,
            listFontSize = listFontSize,
            listLineHeight = listLineHeight,
            listVerticalPadding = listVerticalPadding,
            buttonStyle = buttonStyle
        )
    }
}

@Composable
private fun ItemRow(
    item: ListItem,
    showFavoriteStar: Boolean,
    isSelected: Boolean,
    fontSize: TextUnit,
    lineHeight: TextUnit,
    verticalPadding: Dp,
    showReorderIcon: Boolean = false,
    checkState: Boolean? = null
) {
    val displayName = item.rowDisplayName(showStar = false)
    val rowText = if (showFavoriteStar) "$STAR $displayName" else displayName
    val textStyle = MaterialTheme.typography.bodyLarge.copy(
        fontSize = fontSize,
        lineHeight = lineHeight
    )
    val scrollState = rememberScrollState()
    MarqueeEffect(scrollState, isSelected, key = rowText to isSelected)

    val colors = LocalCannoliColors.current
    PillRow(isSelected = isSelected, verticalPadding = verticalPadding, lineHeight = lineHeight) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (checkState != null) {
                Text(
                    text = if (checkState) "☑" else "☐",
                    style = textStyle,
                    color = if (isSelected) colors.highlightText else colors.text
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            if (showReorderIcon) {
                Text(
                    text = "↕",
                    style = textStyle,
                    color = if (isSelected) colors.highlightText else colors.text
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(scrollState),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (item is ListItem.SubfolderItem) {
                    Text(
                        text = "/",
                        style = textStyle,
                        color = if (isSelected) colors.highlightText else colors.text
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                }
                Text(
                    text = rowText,
                    style = textStyle,
                    color = if (isSelected) colors.highlightText else colors.text,
                    maxLines = 1,
                    softWrap = false
                )
            }
        }
    }
}
