package dev.cannoli.scorza.ui.screens

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentSize
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cannoli.scorza.R
import dev.cannoli.scorza.settings.ArtScale
import dev.cannoli.igm.ui.components.BottomBar
import dev.cannoli.scorza.ui.components.ConfirmOverlay
import dev.cannoli.scorza.ui.components.DialogOverlay
import dev.cannoli.igm.ui.components.List
import dev.cannoli.igm.ui.components.PillRowText
import dev.cannoli.igm.ui.components.ScreenBackground
import dev.cannoli.igm.ui.components.ScreenTitle
import dev.cannoli.igm.ui.components.pillItemHeight
import dev.cannoli.igm.ui.components.screenPadding
import dev.cannoli.igm.ui.theme.LocalCannoliColors
import dev.cannoli.igm.ButtonStyle
import dev.cannoli.scorza.ui.viewmodel.SystemListViewModel
import dev.cannoli.scorza.ui.viewmodel.SystemListViewModel.ListItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun SystemListScreen(
    viewModel: SystemListViewModel,
    backgroundImagePath: String? = null,
    backgroundTint: Int = 0,
    listFontSize: TextUnit = 22.sp,
    listLineHeight: TextUnit = 32.sp,
    listVerticalPadding: Dp = 8.dp,
    dialogState: DialogState = DialogState.None,
    onVisibleRangeChanged: (Int, Int, Boolean) -> Unit = { _, _, _ -> },
    kitchenRunning: Boolean = false,
    title: String = "",
    mainMenuQuit: Boolean = false,
    artWidth: Int = 40,
    artScale: ArtScale = ArtScale.FIT,
    buttonStyle: ButtonStyle = ButtonStyle()
) {
    val state by viewModel.state.collectAsState()
    val itemHeight = pillItemHeight(listLineHeight, listVerticalPadding)

    val selectedItem = state.items.getOrNull(state.selectedIndex)
    val selectedGame = (selectedItem as? ListItem.GameItem)?.game
    val artPath = selectedGame?.artFile?.absolutePath
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
    val hasGameItems = state.items.any { it is ListItem.GameItem }
    val showArt = hasGameItems && selectedArt != null && artWidth > 0

    ScreenBackground(backgroundImagePath = backgroundImagePath, backgroundTint = backgroundTint) {
        if (state.isLoading) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_launcher_foreground),
                    contentDescription = null,
                    modifier = Modifier.size(288.dp)
                )
            }
        } else {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(screenPadding)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = 48.dp)
            ) {
            if (title.isNotEmpty()) {
                ScreenTitle(
                    text = title,
                    fontSize = listFontSize,
                    lineHeight = listLineHeight
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
            if (state.items.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.empty_content),
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
                    key = if (state.reorderMode) null else { _, item ->
                        when (item) {
                            is ListItem.RecentlyPlayedItem -> "recently_played"
                            is ListItem.FavoritesItem -> "favorites"
                            is ListItem.CollectionsFolder -> "collections"
                            is ListItem.PlatformItem -> item.platform.tag
                            is ListItem.CollectionItem -> "col:${item.name}"
                            is ListItem.GameItem -> "game:${item.game.file.absolutePath}"
                            is ListItem.ToolsFolder -> "tools"
                            is ListItem.PortsFolder -> "ports"
                        }
                    }
                ) { index, item ->
                    val label = when (item) {
                        is ListItem.RecentlyPlayedItem -> stringResource(R.string.label_recently_played)
                        is ListItem.FavoritesItem -> stringResource(R.string.label_favorites)
                        is ListItem.CollectionsFolder -> stringResource(R.string.label_collections)
                        is ListItem.PlatformItem -> item.platform.displayName
                        is ListItem.CollectionItem -> dev.cannoli.scorza.model.Collection.stemToDisplayName(item.name)
                        is ListItem.GameItem -> item.game.displayName
                        is ListItem.ToolsFolder -> item.name
                        is ListItem.PortsFolder -> item.name
                    }
                    val showReorder = state.reorderMode && state.selectedIndex == index && (item is ListItem.PlatformItem || item is ListItem.ToolsFolder || item is ListItem.PortsFolder || item is ListItem.CollectionItem || item is ListItem.GameItem)
                    PillRowText(
                        label = label,
                        isSelected = state.selectedIndex == index,
                        fontSize = listFontSize,
                        lineHeight = listLineHeight,
                        verticalPadding = listVerticalPadding,
                        showReorderIcon = showReorder
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
                            modifier = artModifier.clip(RoundedCornerShape(8.dp)),
                            contentScale = artContentScale,
                            filterQuality = FilterQuality.High
                        )
                    }
                }
            }
            }
            }

            val rightItems = if (state.items.isEmpty()) {
                listOf(buttonStyle.west to stringResource(R.string.label_kitchen))
            } else if (kitchenRunning) {
                listOf(buttonStyle.west to stringResource(R.string.label_kitchen), buttonStyle.confirm to stringResource(R.string.label_select))
            } else {
                listOf(buttonStyle.confirm to stringResource(R.string.label_select))
            }
            val leftItems = buildList {
                if (mainMenuQuit) add(buttonStyle.back to stringResource(R.string.label_quit))
                add(buttonStyle.north to stringResource(R.string.label_settings))
            }
            BottomBar(
                modifier = Modifier.align(Alignment.BottomCenter),
                leftItems = leftItems,
                rightItems = rightItems
            )
        }
        }
    }

    if (dialogState is DialogState.QuitConfirm) {
        ConfirmOverlay(message = stringResource(R.string.dialog_quit_confirm), confirmLabel = stringResource(R.string.label_quit))
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
