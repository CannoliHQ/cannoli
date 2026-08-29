package dev.cannoli.ui.components

import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cannoli.ui.ButtonStyle
import dev.cannoli.ui.MENU_GLYPH
import dev.cannoli.ui.R
import dev.cannoli.ui.START_GLYPH
import dev.cannoli.ui.theme.GrayText
import dev.cannoli.ui.theme.LocalCannoliColors
import dev.cannoli.ui.theme.LocalCannoliTypography
import dev.cannoli.ui.theme.LocalScaleFactor
import dev.cannoli.ui.theme.Spacing

enum class HelpGlyph { DPAD, CONFIRM, BACK, NORTH, WEST, START, SELECT, MENU, L1, R1, L2, R2 }

data class HelpEntry(val glyphs: List<HelpGlyph>, @StringRes val descRes: Int)
data class HelpGroup(@StringRes val headerRes: Int, val entries: List<HelpEntry>)

private fun glyphLabel(glyph: HelpGlyph, buttonStyle: ButtonStyle): String = when (glyph) {
    HelpGlyph.CONFIRM -> buttonStyle.confirm
    HelpGlyph.BACK -> buttonStyle.back
    HelpGlyph.NORTH -> buttonStyle.north
    HelpGlyph.WEST -> buttonStyle.west
    HelpGlyph.START -> START_GLYPH
    HelpGlyph.SELECT -> "SELECT"
    HelpGlyph.MENU -> MENU_GLYPH
    HelpGlyph.L1 -> "L1"
    HelpGlyph.R1 -> "R1"
    HelpGlyph.L2 -> "L2"
    HelpGlyph.R2 -> "R2"
    HelpGlyph.DPAD -> ""
}

/**
 * What a screen answers to, as glyph and description. The keyboard and the guide viewer both use
 * this; each supplies its own [groups], since only the screen knows what its buttons do.
 */
@Composable
fun HelpOverlay(
    @StringRes titleRes: Int,
    groups: List<HelpGroup>,
    titleFontSize: TextUnit = 22.sp,
    titleLineHeight: TextUnit = 32.sp,
    buttonStyle: ButtonStyle = ButtonStyle()
) {
    val colors = LocalCannoliColors.current

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .padding(screenPadding)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = footerReservation())
        ) {
            ScreenTitle(
                text = stringResource(titleRes),
                fontSize = titleFontSize,
                lineHeight = titleLineHeight,
            )
            Spacer(modifier = Modifier.height(Spacing.Md))
            Row(
                modifier = Modifier
                    .fillMaxWidth(0.9f)
                    .align(Alignment.CenterHorizontally),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // One group to a column while there are two, so a screen with a single group does
                // not leave half the width empty.
                if (groups.size > 1) {
                    HelpColumn(groups.take(1), buttonStyle)
                    HelpColumn(groups.drop(1), buttonStyle)
                } else {
                    HelpColumn(groups, buttonStyle)
                }
            }
        }

        BottomBar(
            modifier = Modifier.align(Alignment.BottomCenter),
            leftItems = listOf(buttonStyle.back to stringResource(R.string.label_close)),
            rightItems = emptyList()
        )
    }
}

@Composable
private fun HelpColumn(
    groups: List<HelpGroup>,
    buttonStyle: ButtonStyle,
    modifier: Modifier = Modifier
) {
    val typo = LocalCannoliTypography.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Spacing.Md)) {
        groups.forEach { group ->
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.Sm)) {
                Text(
                    text = stringResource(group.headerRes).uppercase(),
                    style = typo.labelSmall.copy(letterSpacing = 1.sp),
                    color = GrayText
                )
                group.entries.forEach { HelpRow(it, buttonStyle) }
            }
        }
    }
}

@Composable
private fun HelpRow(entry: HelpEntry, buttonStyle: ButtonStyle) {
    LegendPill(label = stringResource(entry.descRes)) {
        entry.glyphs.forEach { glyph ->
            if (glyph == HelpGlyph.DPAD) GlyphPill { DpadIcon() } else GlyphPill(glyphLabel(glyph, buttonStyle))
        }
    }
}

@Composable
private fun DpadIcon() {
    val accent = LocalCannoliColors.current.accent
    val sf = LocalScaleFactor.current
    Canvas(modifier = Modifier.size((14 * sf).dp)) {
        val thickness = size.minDimension * 0.36f
        val corner = CornerRadius(thickness * 0.3f, thickness * 0.3f)
        drawRoundRect(
            color = accent,
            topLeft = Offset((size.width - thickness) / 2f, 0f),
            size = Size(thickness, size.height),
            cornerRadius = corner
        )
        drawRoundRect(
            color = accent,
            topLeft = Offset(0f, (size.height - thickness) / 2f),
            size = Size(size.width, thickness),
            cornerRadius = corner
        )
    }
}
