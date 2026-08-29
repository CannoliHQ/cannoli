package dev.cannoli.igm

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cannoli.ui.components.BottomBar
import dev.cannoli.ui.components.List
import dev.cannoli.ui.components.PillRowKeyValue
import dev.cannoli.ui.components.PillRowText
import dev.cannoli.ui.components.ScreenBackground
import dev.cannoli.ui.components.ScreenTitle
import dev.cannoli.ui.components.footerReservation
import dev.cannoli.ui.components.pillInternalPadding
import dev.cannoli.ui.components.pillItemHeight
import dev.cannoli.ui.components.pillVerticalPadding
import dev.cannoli.ui.components.screenInsets
import dev.cannoli.ui.theme.LocalCannoliColors
import dev.cannoli.ui.theme.LocalCannoliTypography
import dev.cannoli.ui.theme.Spacing

// A long RetroArch sublabel does not fit on one screen, so Up and Down page through it.
private const val DESCRIPTION_SCROLL_STEP_PX = 220f

@Composable
fun IGMSettingsScreen(
    title: String,
    items: kotlin.collections.List<IGMSettingsItem>,
    selectedIndex: Int,
    bottomBarLeft: kotlin.collections.List<Pair<String, String>>,
    bottomBarRight: kotlin.collections.List<Pair<String, String>>,
    coreInfo: String = "",
    /** Marks the row that is picked up, so it is obvious which one Up and Down are moving. */
    reorderingIndex: Int? = null,
    description: String? = null,
    descriptionScroll: Int = 0,
    fontSize: TextUnit = 22.sp,
    lineHeight: TextUnit = 32.sp,
    /**
     * How much of the game to hide. A list of shaders is judged by what the shader does to the
     * picture, so that list gets a narrow, barely dimmed panel and leaves the rest of the frame
     * visible. Everything else covers the screen as before.
     */
    dimAlpha: Float = 0.85f,
    widthFraction: Float = 1f,
) {
    val typo = LocalCannoliTypography.current
    val verticalPadding = pillVerticalPadding()
    val itemHeight = pillItemHeight(lineHeight, verticalPadding)
    val colors = LocalCannoliColors.current

    ScreenBackground(backgroundImagePath = null, backgroundAlpha = dimAlpha, backgroundColor = Color.Black) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(screenInsets())
        ) {
            if (description != null) {
                val descriptionScrollState = rememberScrollState()
                // Scroll by the change since the last look, so holding a direction keeps moving and
                // reopening the description starts at the top.
                var lastScrollStep by remember(description) { mutableIntStateOf(0) }
                LaunchedEffect(descriptionScroll, description) {
                    val delta = descriptionScroll - lastScrollStep
                    lastScrollStep = descriptionScroll
                    if (delta != 0) {
                        descriptionScrollState.animateScrollBy(delta * DESCRIPTION_SCROLL_STEP_PX)
                    }
                }
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = footerReservation())
                        .verticalScroll(descriptionScrollState)
                ) {
                    ScreenTitle(
                        text = items.getOrNull(selectedIndex)?.label ?: "",
                        fontSize = fontSize,
                        lineHeight = lineHeight
                    )
                    Spacer(modifier = Modifier.height(Spacing.Md))
                    Text(
                        text = description,
                        style = typo.bodyMedium.copy(
                            color = colors.text.copy(alpha = 0.8f)
                        ),
                        modifier = Modifier.padding(start = pillInternalPadding())
                    )
                }
            } else {
                Column(
                    // Narrowed here rather than around the whole screen, so the legend below still
                    // spans the display and its right-hand item stays on the right.
                    modifier = Modifier
                        .fillMaxWidth(widthFraction)
                        .padding(bottom = footerReservation())
                ) {
                    ScreenTitle(
                        text = title,
                        fontSize = fontSize,
                        lineHeight = lineHeight
                    )
                    Spacer(modifier = Modifier.height(Spacing.Sm))
                    List(
                        items = items,
                        selectedIndex = selectedIndex,
                        itemHeight = itemHeight
                    ) { _, item, isSelected ->
                        if (item.value != null) {
                            PillRowKeyValue(
                                label = item.label,
                                value = item.value,
                                isSelected = isSelected,
                                fontSize = fontSize,
                                lineHeight = lineHeight,
                                verticalPadding = verticalPadding
                            )
                        } else {
                            PillRowText(
                                label = item.label,
                                isSelected = isSelected,
                                fontSize = fontSize,
                                lineHeight = lineHeight,
                                verticalPadding = verticalPadding,
                                showReorderIcon = reorderingIndex != null && isSelected,
                            )
                        }
                    }
                }

                if (coreInfo.isNotEmpty()) {
                    Text(
                        text = coreInfo,
                        style = typo.labelSmall.copy(color = colors.text),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 44.dp)
                            .fillMaxWidth(0.9f)
                    )
                }
            }

            BottomBar(
                // Outside the narrowed column: the legend spans the screen even when the list
                // steps aside, or its right-hand item lands in the middle of the display.
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
                leftItems = bottomBarLeft,
                rightItems = if (description != null) emptyList() else bottomBarRight
            )
        }
    }
}
