package dev.cannoli.scorza.ui.screens

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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cannoli.scorza.romm.RommPlatform
import dev.cannoli.ui.ButtonStyle
import dev.cannoli.ui.R
import dev.cannoli.ui.components.BottomBar
import dev.cannoli.ui.components.CannoliProgressBar
import dev.cannoli.ui.components.List
import dev.cannoli.ui.components.PillRowKeyValue
import dev.cannoli.ui.components.ScreenBackground
import dev.cannoli.ui.components.ScreenTitle
import dev.cannoli.ui.components.footerReservation
import dev.cannoli.ui.components.pillItemHeight
import dev.cannoli.ui.components.screenPadding
import dev.cannoli.ui.theme.Spacing

@Composable
fun RommPlatformListScreen(
    platforms: List<RommPlatform>,
    selectedIndex: Int,
    scrollTarget: Int,
    showCollectionsRow: Boolean = false,
    collectionCount: Int = 0,
    emptyMessage: String? = null,
    progress: Float? = null,
    syncing: Boolean = false,
    backgroundImagePath: String?,
    backgroundTint: Int,
    listFontSize: TextUnit = 22.sp,
    listLineHeight: TextUnit = 32.sp,
    listVerticalPadding: Dp = 6.dp,
    onListStateChanged: ((androidx.compose.foundation.lazy.LazyListState?) -> Unit)? = null,
    buttonStyle: ButtonStyle = ButtonStyle(),
) {
    val itemHeight = pillItemHeight(listLineHeight, listVerticalPadding)
    val totalItemCount = platforms.size + (if (showCollectionsRow) 1 else 0)
    ScreenBackground(backgroundImagePath = backgroundImagePath, backgroundTint = backgroundTint) {
        Box(modifier = Modifier.fillMaxSize().padding(screenPadding)) {
            Column(modifier = Modifier.fillMaxSize().padding(bottom = footerReservation())) {
                ScreenTitle(
                    text = stringResource(R.string.romm_browse_title),
                    fontSize = listFontSize,
                    lineHeight = listLineHeight,
                )
                Spacer(modifier = Modifier.height(Spacing.Sm))
                val offset = if (showCollectionsRow) 1 else 0
                val clampedIndex = selectedIndex.coerceIn(0, (totalItemCount - 1).coerceAtLeast(0))
                List(
                    items = (0 until totalItemCount).toList(),
                    selectedIndex = clampedIndex,
                    itemHeight = itemHeight,
                    scrollTarget = scrollTarget,
                    onListStateChanged = onListStateChanged,
                ) { index, _, isSelected ->
                    if (showCollectionsRow && index == 0) {
                        PillRowKeyValue(
                            label = stringResource(R.string.romm_collections_title),
                            value = collectionCount.toString(),
                            isSelected = isSelected,
                            fontSize = listFontSize,
                            lineHeight = listLineHeight,
                            verticalPadding = listVerticalPadding,
                        )
                    } else {
                        val platform = platforms[index - offset]
                        PillRowKeyValue(
                            label = platform.displayName,
                            value = platform.romCount.toString(),
                            isSelected = isSelected,
                            fontSize = listFontSize,
                            lineHeight = listLineHeight,
                            verticalPadding = listVerticalPadding,
                        )
                    }
                }
            }
            if (platforms.isEmpty() && emptyMessage != null) {
                val titleFontSize = listFontSize * 1.2f
                val titleLineHeight = listLineHeight * 1.2f
                Column(
                    modifier = Modifier.align(Alignment.Center).fillMaxWidth(0.85f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (syncing) {
                        Text(
                            text = emptyMessage.substringBefore('\n'),
                            textAlign = TextAlign.Center,
                            fontSize = titleFontSize,
                            lineHeight = titleLineHeight,
                        )
                        Spacer(modifier = Modifier.height(Spacing.Sm))
                        Text(
                            text = emptyMessage.substringAfter('\n', "").ifBlank { " " },
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontSize = listFontSize,
                            lineHeight = listLineHeight,
                        )
                        Spacer(modifier = Modifier.height(Spacing.Md))
                        CannoliProgressBar(
                            progress = progress ?: 0f,
                            modifier = Modifier.widthIn(max = 320.dp),
                        )
                    } else {
                        Text(
                            text = emptyMessage,
                            textAlign = TextAlign.Center,
                            fontSize = titleFontSize,
                            lineHeight = titleLineHeight,
                        )
                    }
                }
            }
            if (!(platforms.isEmpty() && syncing)) {
                BottomBar(
                    modifier = Modifier.align(Alignment.BottomCenter),
                    leftItems = listOf(
                        buttonStyle.back to stringResource(R.string.label_back),
                        buttonStyle.north to stringResource(R.string.label_settings),
                    ),
                    rightItems = listOf(buttonStyle.confirm to stringResource(R.string.label_select)),
                )
            }
        }
    }
}
