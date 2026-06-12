package dev.cannoli.scorza.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cannoli.scorza.romm.art.ArtFetchResults
import dev.cannoli.ui.ButtonStyle
import dev.cannoli.ui.R
import dev.cannoli.ui.components.BottomBar
import dev.cannoli.ui.components.List
import dev.cannoli.ui.components.ScreenBackground
import dev.cannoli.ui.components.ScreenTitle
import dev.cannoli.ui.components.footerReservation
import dev.cannoli.ui.components.pillItemHeight
import dev.cannoli.ui.components.screenPadding
import dev.cannoli.ui.theme.LocalCannoliColors
import dev.cannoli.ui.theme.LocalCannoliFont
import dev.cannoli.ui.theme.Spacing

data class RommArtIssueRow(val text: String, val isHeader: Boolean)

/** The scrollable problem-game rows (no-match section then failed section). Shared with input. */
fun rommArtIssueRows(
    results: ArtFetchResults,
    noMatchHeader: String,
    failedHeader: String,
): List<RommArtIssueRow> = buildList {
    if (results.noMatch.isNotEmpty()) {
        add(RommArtIssueRow(noMatchHeader, true))
        results.noMatch.forEach { add(RommArtIssueRow(it, false)) }
    }
    if (results.failed.isNotEmpty()) {
        add(RommArtIssueRow(failedHeader, true))
        results.failed.forEach { add(RommArtIssueRow(it, false)) }
    }
}

@Composable
fun RommArtResultsScreen(
    results: ArtFetchResults,
    selectedIndex: Int,
    backgroundImagePath: String?,
    backgroundTint: Int,
    listFontSize: TextUnit = 22.sp,
    listLineHeight: TextUnit = 32.sp,
    listVerticalPadding: Dp = 6.dp,
    buttonStyle: ButtonStyle = ButtonStyle(),
) {
    val rows = rommArtIssueRows(
        results,
        stringResource(R.string.romm_art_section_no_match),
        stringResource(R.string.romm_art_section_failed),
    )
    ScreenBackground(backgroundImagePath = backgroundImagePath, backgroundTint = backgroundTint) {
        Box(modifier = Modifier.fillMaxSize().padding(screenPadding)) {
            Column(modifier = Modifier.fillMaxSize().padding(bottom = footerReservation())) {
                ScreenTitle(
                    text = stringResource(R.string.romm_art_results_title),
                    fontSize = listFontSize,
                    lineHeight = listLineHeight,
                )
                Spacer(modifier = Modifier.height(Spacing.Sm))
                CountRow(stringResource(R.string.romm_art_added), results.added, listFontSize)
                CountRow(stringResource(R.string.romm_art_already), results.alreadyHadArt, listFontSize)
                CountRow(stringResource(R.string.romm_art_no_match), results.noMatch.size, listFontSize)
                CountRow(stringResource(R.string.romm_art_failed), results.failed.size, listFontSize)
                Spacer(modifier = Modifier.height(Spacing.Sm))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(LocalCannoliColors.current.text.copy(alpha = 0.2f))
                )
                Spacer(modifier = Modifier.height(Spacing.Sm))
                List(
                    items = rows,
                    selectedIndex = selectedIndex.coerceIn(0, (rows.size - 1).coerceAtLeast(0)),
                    itemHeight = pillItemHeight(listLineHeight, listVerticalPadding),
                ) { _, row, _ ->
                    IssueRow(row, listFontSize, listVerticalPadding)
                }
            }
            BottomBar(
                modifier = Modifier.align(Alignment.BottomCenter),
                leftItems = emptyList(),
                rightItems = listOf(buttonStyle.confirm to stringResource(R.string.romm_art_done)),
            )
        }
    }
}

@Composable
private fun CountRow(label: String, count: Int, fontSize: TextUnit) {
    val font = LocalCannoliFont.current
    val color = LocalCannoliColors.current.text
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp)) {
        Text(label, color = color, fontFamily = font, fontSize = fontSize, modifier = Modifier.weight(1f))
        Text("$count", color = color, fontFamily = font, fontSize = fontSize)
    }
}

@Composable
private fun IssueRow(row: RommArtIssueRow, fontSize: TextUnit, verticalPadding: Dp) {
    val colors = LocalCannoliColors.current
    val font = LocalCannoliFont.current
    if (row.isHeader) {
        Text(
            text = row.text.uppercase(),
            color = colors.accent,
            fontFamily = font,
            fontSize = (fontSize.value * 0.7f).sp,
            letterSpacing = 1.5.sp,
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, top = 12.dp, bottom = 4.dp),
        )
    } else {
        Text(
            text = row.text,
            color = colors.text,
            fontFamily = font,
            fontSize = fontSize,
            modifier = Modifier.fillMaxWidth().padding(start = 24.dp, top = verticalPadding, bottom = verticalPadding),
        )
    }
}
