package dev.cannoli.scorza.libretro

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import dev.cannoli.scorza.R
import dev.cannoli.scorza.ui.components.BottomBar
import dev.cannoli.scorza.ui.components.List
import dev.cannoli.scorza.ui.components.PillRowKeyValue
import dev.cannoli.scorza.ui.components.PillRowText
import dev.cannoli.scorza.ui.components.ScreenBackground
import dev.cannoli.scorza.ui.components.ScreenTitle
import dev.cannoli.scorza.ui.components.pillInternalH
import dev.cannoli.scorza.ui.components.pillItemHeight
import dev.cannoli.scorza.ui.components.screenPadding
import dev.cannoli.scorza.settings.ButtonLabelSet
import dev.cannoli.scorza.ui.theme.LocalCannoliColors

private val verticalPadding = 8.dp

data class IGMSettingsItem(
    val label: String,
    val value: String? = null,
    val hint: String? = null
)

@Composable
fun IGMSettingsScreen(
    title: String,
    items: List<IGMSettingsItem>,
    selectedIndex: Int,
    coreInfo: String = "",
    description: String? = null,
    bottomBarLeft: List<Pair<String, String>> = emptyList(),
    bottomBarRight: List<Pair<String, String>> = emptyList(),
    fontSize: TextUnit = 22.sp,
    lineHeight: TextUnit = 32.sp,
    buttonLabelSet: ButtonLabelSet = ButtonLabelSet.PLUMBER
) {
    val resolvedLeft = bottomBarLeft.ifEmpty { listOf(buttonLabelSet.back to stringResource(R.string.label_back)) }
    val resolvedRight = bottomBarRight.ifEmpty { listOf("←→" to stringResource(R.string.label_change), buttonLabelSet.confirm to stringResource(R.string.label_select)) }
    val itemHeight = pillItemHeight(lineHeight, verticalPadding)
    val colors = LocalCannoliColors.current

    ScreenBackground(backgroundImagePath = null, backgroundAlpha = 0.85f) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(screenPadding)
        ) {
            if (description != null) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = 48.dp)
                ) {
                    ScreenTitle(
                        text = items.getOrNull(selectedIndex)?.label ?: "",
                        fontSize = fontSize,
                        lineHeight = lineHeight
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = 16.sp,
                            lineHeight = 22.sp,
                            color = colors.text.copy(alpha = 0.8f)
                        ),
                        modifier = Modifier.padding(start = pillInternalH)
                    )
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 48.dp)
                ) {
                    ScreenTitle(
                        text = title,
                        fontSize = fontSize,
                        lineHeight = lineHeight
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    List(
                        items = items,
                        selectedIndex = selectedIndex,
                        itemHeight = itemHeight
                    ) { index, item ->
                        if (item.value != null) {
                            PillRowKeyValue(
                                label = item.label,
                                value = item.value,
                                isSelected = index == selectedIndex,
                                fontSize = fontSize,
                                lineHeight = lineHeight,
                                verticalPadding = verticalPadding
                            )
                        } else {
                            PillRowText(
                                label = item.label,
                                isSelected = index == selectedIndex,
                                fontSize = fontSize,
                                lineHeight = lineHeight,
                                verticalPadding = verticalPadding
                            )
                        }
                    }
                }

                if (coreInfo.isNotEmpty()) {
                    Text(
                        text = coreInfo,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontSize = 14.sp,
                            color = colors.text.copy(alpha = 0.4f)
                        ),
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 44.dp)
                    )
                }
            }

            BottomBar(
                modifier = Modifier.align(Alignment.BottomCenter),
                leftItems = resolvedLeft,
                rightItems = if (description != null) emptyList() else resolvedRight
            )
        }
    }
}
