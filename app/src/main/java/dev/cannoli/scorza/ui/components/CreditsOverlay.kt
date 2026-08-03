package dev.cannoli.scorza.ui.components

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import dev.cannoli.scorza.R
import dev.cannoli.scorza.i18n.LanguageCatalog
import dev.cannoli.ui.ButtonStyle
import dev.cannoli.ui.components.List
import dev.cannoli.ui.components.ListSection
import dev.cannoli.ui.components.PillRowKeyValue
import dev.cannoli.ui.components.PillRowText
import dev.cannoli.ui.components.SectionedList
import dev.cannoli.ui.components.pillItemHeight

enum class CreditsCategory(@StringRes val titleRes: Int) {
    Cores(R.string.credits_cores),
    Shaders(R.string.credits_shaders),
    Fonts(R.string.credits_fonts),
    Libraries(R.string.credits_libraries),
    Localization(R.string.credits_localization),
}

sealed interface CreditsRootRow {
    data class Person(val entry: CreditEntry) : CreditsRootRow
    data class Category(val category: CreditsCategory) : CreditsRootRow
}

val CREDITS_ROOT_ROWS: List<CreditsRootRow> =
    CREDITS_INSPIRATION.map { CreditsRootRow.Person(it) } +
        CreditsCategory.entries.map { CreditsRootRow.Category(it) }

fun creditEntriesFor(category: CreditsCategory): List<CreditEntry> = when (category) {
    CreditsCategory.Cores -> CREDITS_CORES
    CreditsCategory.Shaders -> CREDITS_SHADERS
    CreditsCategory.Fonts -> CREDITS_FONTS
    CreditsCategory.Libraries -> CREDITS_LIBRARIES
    CreditsCategory.Localization -> emptyList()
}

fun creditsItemCount(category: CreditsCategory): Int = when (category) {
    CreditsCategory.Localization -> CREDITS_LOCALIZATION.sumOf { it.contributors.size }
    else -> creditEntriesFor(category).size
}

@Composable
fun CreditsOverlay(
    selectedIndex: Int,
    scrollTarget: Int,
    backgroundImagePath: String?,
    backgroundTint: Int,
    listFontSize: TextUnit,
    listLineHeight: TextUnit,
    listVerticalPadding: Dp,
    buttonStyle: ButtonStyle = ButtonStyle(),
    onListStateChanged: ((androidx.compose.foundation.lazy.LazyListState?) -> Unit)? = null
) {
    val itemHeight = pillItemHeight(listLineHeight, listVerticalPadding)
    val selectedRow = CREDITS_ROOT_ROWS.getOrNull(selectedIndex)
    ListDialogScreen(
        backgroundImagePath = backgroundImagePath,
        backgroundTint = backgroundTint,
        title = stringResource(R.string.credits_title),
        listFontSize = listFontSize,
        listLineHeight = listLineHeight,
        fullWidth = true,
        buttonStyle = buttonStyle,
        rightBottomItems = if (selectedRow is CreditsRootRow.Category) {
            listOf(buttonStyle.confirm to stringResource(R.string.label_select))
        } else {
            emptyList()
        }
    ) {
        List(
            items = CREDITS_ROOT_ROWS,
            selectedIndex = selectedIndex,
            scrollTarget = scrollTarget,
            itemHeight = itemHeight,
            onListStateChanged = onListStateChanged
        ) { _, row, isSelected ->
            when (row) {
                is CreditsRootRow.Person -> PillRowKeyValue(
                    label = row.entry.name,
                    value = row.entry.detail,
                    isSelected = isSelected,
                    fontSize = listFontSize,
                    lineHeight = listLineHeight,
                    verticalPadding = listVerticalPadding
                )
                is CreditsRootRow.Category -> PillRowText(
                    label = stringResource(row.category.titleRes),
                    isSelected = isSelected,
                    fontSize = listFontSize,
                    lineHeight = listLineHeight,
                    verticalPadding = listVerticalPadding
                )
            }
        }
    }
}

@Composable
fun CreditsCategoryOverlay(
    category: CreditsCategory,
    selectedIndex: Int,
    scrollTarget: Int,
    backgroundImagePath: String?,
    backgroundTint: Int,
    listFontSize: TextUnit,
    listLineHeight: TextUnit,
    listVerticalPadding: Dp,
    buttonStyle: ButtonStyle = ButtonStyle(),
    onListStateChanged: ((androidx.compose.foundation.lazy.LazyListState?) -> Unit)? = null
) {
    val itemHeight = pillItemHeight(listLineHeight, listVerticalPadding)
    ListDialogScreen(
        backgroundImagePath = backgroundImagePath,
        backgroundTint = backgroundTint,
        title = stringResource(category.titleRes),
        listFontSize = listFontSize,
        listLineHeight = listLineHeight,
        fullWidth = true,
        buttonStyle = buttonStyle,
        rightBottomItems = emptyList()
    ) {
        if (category == CreditsCategory.Localization) {
            val sections = CREDITS_LOCALIZATION.map { credit ->
                ListSection(
                    header = LanguageCatalog.byTag(credit.languageTag)?.nativeName ?: credit.languageTag,
                    items = credit.contributors
                )
            }
            SectionedList(
                sections = sections,
                selectedIndex = selectedIndex,
                fontSize = listFontSize,
                lineHeight = listLineHeight,
                verticalPadding = listVerticalPadding,
                itemHeight = itemHeight,
                scrollTarget = scrollTarget,
                onListStateChanged = onListStateChanged
            ) { _, name, isSelected ->
                PillRowText(
                    label = name,
                    isSelected = isSelected,
                    fontSize = listFontSize,
                    lineHeight = listLineHeight,
                    verticalPadding = listVerticalPadding
                )
            }
        } else {
            List(
                items = creditEntriesFor(category),
                selectedIndex = selectedIndex,
                scrollTarget = scrollTarget,
                itemHeight = itemHeight,
                onListStateChanged = onListStateChanged
            ) { _, entry, isSelected ->
                PillRowKeyValue(
                    label = entry.name,
                    value = entry.detail,
                    isSelected = isSelected,
                    fontSize = listFontSize,
                    lineHeight = listLineHeight,
                    verticalPadding = listVerticalPadding
                )
            }
        }
    }
}
