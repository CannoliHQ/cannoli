package dev.cannoli.igm

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cannoli.ui.ButtonStyle
import dev.cannoli.ui.DPAD_HORIZONTAL
import dev.cannoli.ui.components.BottomBar
import dev.cannoli.ui.components.List
import dev.cannoli.ui.components.PillRowKeyValue
import dev.cannoli.ui.components.pillItemHeight
import dev.cannoli.ui.components.pillVerticalPadding
import dev.cannoli.ui.components.screenInsets
import dev.cannoli.ui.theme.LocalCannoliTypography
import dev.cannoli.ui.theme.LocalScaleFactor
import dev.cannoli.ui.theme.Spacing

/**
 * Picks one of a list of visual assets while the game keeps running underneath.
 *
 * Unlike every other IGM screen this one paints no dimming background, because the thing being
 * chosen is the picture on screen: an overlay's artwork sits in the pillars either side of the
 * game, which is exactly where a normal menu panel would be. Chrome collapses to a strip along the
 * bottom so the asset can be judged at full size, and the caller applies each move to the running
 * emulator, which is what makes this a preview rather than a list of names.
 *
 * Nothing here knows what an overlay is, so a shader list works the same way.
 */
@Composable
fun LivePreviewPicker(
    title: String,
    items: kotlin.collections.List<String>,
    index: Int,
    labels: ButtonStyle,
    canRestore: Boolean = false,
    fontSize: TextUnit = 22.sp,
    lineHeight: TextUnit = 32.sp,
    modifier: Modifier = Modifier,
) {
    val sf = LocalScaleFactor.current
    val typo = LocalCannoliTypography.current
    val current = items.getOrNull(index).orEmpty()

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                // The fade carries the strip's text over whatever the game happens to be drawing,
                // without a hard edge that would read as a panel.
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.94f)),
                    ),
                )
                .padding(screenInsets())
                .padding(top = (34 * sf).dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy((14 * sf).dp),
            ) {
                Arrow("◀", items.size, fontSize)
                Text(
                    text = current,
                    style = typo.bodyLarge,
                    color = Color.White,
                    fontSize = fontSize,
                    lineHeight = lineHeight,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
                Arrow("▶", items.size, fontSize)
            }

            Spacer(modifier = Modifier.height(Spacing.Sm))

            BottomBar(
                modifier = Modifier.fillMaxWidth(),
                leftItems = listOf(
                    labels.back to stringResource(dev.cannoli.ui.R.string.label_back),
                    DPAD_HORIZONTAL to stringResource(dev.cannoli.ui.R.string.label_change),
                ),
                // Only ever the one action. A move is already applied and already staged, so Back
                // is the way out and the save prompt leaving the tree decides the rest; and there
                // is nothing to configure, because how a bezel looks belongs to the artwork.
                rightItems = if (!canRestore) emptyList() else listOf(
                    labels.west to stringResource(dev.cannoli.ui.R.string.label_use_platform)
                ),
            )
        }
    }
}

// Dimmed to nothing at one item so the strip does not advertise a move that cannot happen.
@Composable
private fun Arrow(glyph: String, count: Int, fontSize: TextUnit) {
    Text(
        text = glyph,
        color = Color.White.copy(alpha = if (count > 1) 0.75f else 0.15f),
        fontSize = fontSize * 0.8f,
    )
}
