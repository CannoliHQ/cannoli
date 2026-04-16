package dev.cannoli.scorza.libretro

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.annotation.StringRes
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import dev.cannoli.igm.ui.theme.Spacing
import androidx.compose.ui.unit.sp
import dev.cannoli.scorza.R
import dev.cannoli.scorza.input.ProfileManager
import dev.cannoli.igm.ui.components.BottomBar
import dev.cannoli.igm.ui.components.List
import dev.cannoli.igm.ui.components.PillRowKeyValue
import dev.cannoli.igm.ui.components.ScreenBackground
import dev.cannoli.igm.ui.components.ScreenTitle
import dev.cannoli.igm.ui.components.pillItemHeight
import dev.cannoli.igm.ui.theme.LocalCannoliColors
import dev.cannoli.igm.ButtonStyle
import dev.cannoli.igm.ELLIPSIS
import dev.cannoli.igm.ui.components.screenPadding

private val verticalPadding = 8.dp

@Composable
fun ControlsScreen(
    input: LibretroInput,
    selectedIndex: Int,
    listeningIndex: Int,
    listenTimeoutMs: Int = 3000,
    listenCountdownMs: Int = 0,
    profileName: String? = null,
    @StringRes titleRes: Int = R.string.title_controls,
    canUnmapSelected: Boolean = false,
    fontSize: TextUnit = 22.sp,
    lineHeight: TextUnit = 32.sp,
    buttonStyle: ButtonStyle = ButtonStyle()
) {
    val itemHeight = pillItemHeight(lineHeight, verticalPadding)
    val screenTitle = profileName ?: stringResource(titleRes)

    ScreenBackground(backgroundImagePath = null, backgroundAlpha = 0.85f) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(screenPadding)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 48.dp)
            ) {
                ScreenTitle(
                    text = screenTitle,
                    fontSize = fontSize,
                    lineHeight = lineHeight
                )
                Spacer(modifier = Modifier.height(Spacing.Sm))
                List(
                    items = input.buttons,
                    selectedIndex = selectedIndex,
                    itemHeight = itemHeight
                ) { index, button, isSelected ->
                    val value = if (index == listeningIndex) {
                        ELLIPSIS
                    } else {
                        LibretroInput.keyCodeName(input.getKeyCodeFor(button))
                    }
                    PillRowKeyValue(
                        label = button.label,
                        value = value,
                        isSelected = isSelected,
                        fontSize = fontSize,
                        lineHeight = lineHeight,
                        verticalPadding = verticalPadding
                    )
                }
            }

            val bottomLeft = if (listeningIndex >= 0) {
                emptyList()
            } else {
                listOf(buttonStyle.back to stringResource(R.string.label_back))
            }
            val bottomRight = if (listeningIndex >= 0) {
                emptyList()
            } else {
                buildList {
                    if (canUnmapSelected) add(buttonStyle.north to stringResource(R.string.label_unmap))
                    add(buttonStyle.confirm to stringResource(R.string.label_remap))
                }
            }

            BottomBar(
                modifier = Modifier.align(Alignment.BottomCenter),
                leftItems = bottomLeft,
                rightItems = bottomRight
            )
        }

        if (listeningIndex >= 0) {
            val colors = LocalCannoliColors.current
            val buttonLabel = input.buttons.getOrNull(listeningIndex)?.label.orEmpty()
            val remaining = (listenTimeoutMs - listenCountdownMs).coerceAtLeast(0)
            val progress = if (listenTimeoutMs > 0) {
                (remaining.toFloat() / listenTimeoutMs.toFloat()).coerceIn(0f, 1f)
            } else 0f

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.92f)),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth(0.7f)
                ) {
                    Text(
                        text = buttonLabel,
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontSize = 24.sp,
                            color = colors.text
                        )
                    )
                    Spacer(modifier = Modifier.height(Spacing.Sm))
                    Text(
                        text = stringResource(R.string.press_button_prompt),
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = 16.sp,
                            color = colors.text.copy(alpha = 0.6f)
                        )
                    )
                    Spacer(modifier = Modifier.height(Spacing.Lg))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.5f)
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(colors.text.copy(alpha = 0.2f))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(progress)
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(colors.highlight)
                        )
                    }
                }
            }
        }
    }
}
