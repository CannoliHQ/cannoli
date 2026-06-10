package dev.cannoli.scorza.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import coil.compose.AsyncImage
import dev.cannoli.ui.theme.Radius
import dev.cannoli.scorza.romm.LocalState
import dev.cannoli.scorza.romm.RommArtUrl
import dev.cannoli.scorza.ui.viewmodel.RommGameRow
import dev.cannoli.ui.ButtonStyle
import dev.cannoli.ui.R
import dev.cannoli.ui.components.BottomBar
import dev.cannoli.ui.components.List
import dev.cannoli.ui.components.PillRowKeyValue
import dev.cannoli.ui.components.ScreenBackground
import dev.cannoli.ui.components.ScreenTitle
import dev.cannoli.ui.components.footerReservation
import dev.cannoli.ui.components.pillItemHeight
import dev.cannoli.ui.components.screenPadding
import dev.cannoli.ui.theme.Spacing

@Composable
fun RommGameListScreen(
    title: String,
    games: List<RommGameRow>,
    selectedIndex: Int,
    scrollTarget: Int,
    host: String,
    artWidth: Int,
    downloadIcon: String,
    imageLoader: coil.ImageLoader,
    backgroundImagePath: String?,
    backgroundTint: Int,
    listFontSize: TextUnit = 22.sp,
    listLineHeight: TextUnit = 32.sp,
    listVerticalPadding: Dp = 6.dp,
    onListStateChanged: ((androidx.compose.foundation.lazy.LazyListState?) -> Unit)? = null,
    buttonStyle: ButtonStyle = ButtonStyle(),
) {
    val itemHeight = pillItemHeight(listLineHeight, listVerticalPadding)
    val showArt = artWidth > 0 && games.isNotEmpty()

    ScreenBackground(backgroundImagePath = backgroundImagePath, backgroundTint = backgroundTint) {
        Box(modifier = Modifier.fillMaxSize().padding(screenPadding)) {
            Column(modifier = Modifier.fillMaxSize().padding(bottom = footerReservation())) {
                ScreenTitle(
                    text = title,
                    fontSize = listFontSize,
                    lineHeight = listLineHeight,
                )
                Spacer(modifier = Modifier.height(Spacing.Sm))
                Row(modifier = Modifier.fillMaxSize()) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .then(
                                if (showArt) Modifier.fillMaxWidth(1f - artWidth / 100f)
                                else Modifier.fillMaxWidth()
                            )
                    ) {
                        List(
                            items = games,
                            selectedIndex = selectedIndex.coerceIn(0, (games.size - 1).coerceAtLeast(0)),
                            itemHeight = itemHeight,
                            scrollTarget = scrollTarget,
                            onListStateChanged = onListStateChanged,
                        ) { _, row, isSelected ->
                            PillRowKeyValue(
                                label = row.game.name,
                                value = "",
                                isSelected = isSelected,
                                fontSize = listFontSize,
                                lineHeight = listLineHeight,
                                verticalPadding = listVerticalPadding,
                                valueIcon = if (row.localState == LocalState.REMOTE) downloadIcon else null,
                            )
                        }
                    }
                    if (showArt) {
                        val focused = games.getOrNull(selectedIndex)
                        val coverUrl = focused?.let { RommArtUrl.resolve(host, it.game.coverPath) }
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth()
                                .padding(start = 16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            if (coverUrl != null) {
                                AsyncImage(
                                    model = coverUrl,
                                    contentDescription = null,
                                    imageLoader = imageLoader,
                                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(Radius.Lg)),
                                    contentScale = ContentScale.Fit,
                                )
                            }
                        }
                    }
                }
            }
            BottomBar(
                modifier = Modifier.align(Alignment.BottomCenter),
                leftItems = listOf(buttonStyle.back to stringResource(R.string.label_back)),
                rightItems = listOf(
                    buttonStyle.north to stringResource(R.string.label_search),
                    buttonStyle.confirm to stringResource(R.string.label_select),
                ),
            )
        }
    }
}
