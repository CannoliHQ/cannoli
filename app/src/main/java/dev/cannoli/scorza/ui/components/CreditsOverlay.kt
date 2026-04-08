package dev.cannoli.scorza.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import dev.cannoli.igm.ui.components.List
import dev.cannoli.igm.ui.components.PillRowKeyValue
import dev.cannoli.igm.ui.components.pillItemHeight
import dev.cannoli.scorza.R

data class CreditEntry(val name: String, val detail: String)

val CREDITS: List<CreditEntry> = listOf(
    CreditEntry("Hallie", "My beautiful wife who makes me better in every way"),
    CreditEntry("Shaun Inman", "MinUI"),
    CreditEntry("M+ Fonts Project", "OFL"),
    CreditEntry("BPreplay", "OFL"),
    CreditEntry("Nerd Fonts", "OFL"),
    CreditEntry("Apache Commons Compress", "Apache 2.0"),
    CreditEntry("PdfiumAndroid (io.legere)", "Apache 2.0"),
    CreditEntry("XZ for Java", "Public domain"),
    CreditEntry("ZXing", "Apache 2.0"),
    CreditEntry("FBNeo", "Non-commercial"),
    CreditEntry("Gambatte", "GPLv2"),
    CreditEntry("Genesis Plus GX", "Non-commercial"),
    CreditEntry("Handy", "Zlib"),
    CreditEntry("MAME 2003-Plus", "MAME"),
    CreditEntry("Mednafen NGP", "GPLv2"),
    CreditEntry("Mednafen PCE FAST", "GPLv2"),
    CreditEntry("Mednafen VB", "GPLv2"),
    CreditEntry("Mednafen WonderSwan", "GPLv2"),
    CreditEntry("mGBA", "MPLv2.0"),
    CreditEntry("Nestopia", "GPLv2"),
    CreditEntry("PCSX ReARMed", "GPLv2"),
    CreditEntry("PokeMini", "GPLv3"),
    CreditEntry("ProSystem", "GPLv2"),
    CreditEntry("Snes9x", "Non-commercial"),
    CreditEntry("Stella", "GPLv2"),
    CreditEntry("SwanStation", "GPLv3"),
    CreditEntry("crt-easymode by EasyMode", "GPL"),
    CreditEntry("sharp-bilinear by Themaister", "Public domain"),
    CreditEntry("scanline-fract by hunterk", "Public domain"),
    CreditEntry("zfast-crt by SoltanGris42 / metallic77", "GPLv2"),
    CreditEntry("zfast-lcd by SoltanGris42", "GPLv2"),
    CreditEntry("lcd3x by Gigaherz", "Public domain"),
)

@Composable
fun CreditsOverlay(
    selectedIndex: Int,
    scrollTarget: Int,
    backgroundImagePath: String?,
    backgroundTint: Int,
    listFontSize: TextUnit,
    listLineHeight: TextUnit,
    listVerticalPadding: Dp,
    onVisibleRangeChanged: ((Int, Int, Boolean) -> Unit)? = null
) {
    val itemHeight = pillItemHeight(listLineHeight, listVerticalPadding)
    ListDialogScreen(
        backgroundImagePath = backgroundImagePath,
        backgroundTint = backgroundTint,
        title = stringResource(R.string.credits_title),
        listFontSize = listFontSize,
        listLineHeight = listLineHeight,
        fullWidth = true,
        rightBottomItems = emptyList()
    ) {
        List(
            items = CREDITS,
            selectedIndex = selectedIndex,
            scrollTarget = scrollTarget,
            itemHeight = itemHeight,
            onVisibleRangeChanged = onVisibleRangeChanged
        ) { index, entry ->
            PillRowKeyValue(
                label = entry.name,
                value = entry.detail,
                isSelected = selectedIndex == index,
                fontSize = listFontSize,
                lineHeight = listLineHeight,
                verticalPadding = listVerticalPadding
            )
        }
    }
}
