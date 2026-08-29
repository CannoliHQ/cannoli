package dev.cannoli.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import dev.cannoli.ui.ButtonStyle
import dev.cannoli.ui.R

fun keyboardHelpGroups(layout: KeyboardLayout): List<HelpGroup> {
    val typeEntries = buildList {
        add(HelpEntry(listOf(HelpGlyph.DPAD), R.string.kbd_help_move_selection))
        add(HelpEntry(listOf(HelpGlyph.CONFIRM), R.string.kbd_help_press))
        add(HelpEntry(listOf(HelpGlyph.BACK), R.string.kbd_help_backspace))
        if (layout.supportsSpace) add(HelpEntry(listOf(HelpGlyph.NORTH), R.string.kbd_help_space))
        if (layout.supportsCaps) add(HelpEntry(listOf(HelpGlyph.SELECT), R.string.kbd_help_shift))
        if (layout.supportsSymbols) add(HelpEntry(listOf(HelpGlyph.SELECT), R.string.kbd_help_symbols))
    }
    return listOf(
        HelpGroup(R.string.kbd_help_group_type, typeEntries),
        HelpGroup(
            R.string.kbd_help_group_cursor,
            listOf(
                HelpEntry(listOf(HelpGlyph.L1, HelpGlyph.R1), R.string.kbd_help_cursor_move),
                HelpEntry(listOf(HelpGlyph.L2, HelpGlyph.R2), R.string.kbd_help_cursor_jump),
            )
        ),
        HelpGroup(
            R.string.kbd_help_group_action,
            listOf(
                HelpEntry(listOf(HelpGlyph.START), R.string.kbd_help_submit),
                HelpEntry(listOf(HelpGlyph.WEST), R.string.kbd_help_cancel),
            )
        ),
    )
}

@Composable
fun KeyboardHelpOverlay(
    layout: KeyboardLayout,
    titleFontSize: TextUnit = 22.sp,
    titleLineHeight: TextUnit = 32.sp,
    buttonStyle: ButtonStyle = ButtonStyle()
) {
    HelpOverlay(
        titleRes = R.string.kbd_help_title,
        groups = keyboardHelpGroups(layout),
        titleFontSize = titleFontSize,
        titleLineHeight = titleLineHeight,
        buttonStyle = buttonStyle,
    )
}
