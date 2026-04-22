package dev.cannoli.igm

import androidx.annotation.StringRes
import dev.cannoli.ui.R

enum class ShortcutAction(@StringRes val labelRes: Int) {
    SAVE_STATE(R.string.shortcut_action_save_state),
    LOAD_STATE(R.string.shortcut_action_load_state),
    RESET_GAME(R.string.shortcut_action_reset_game),
    SAVE_AND_QUIT(R.string.shortcut_action_save_and_quit),
    CYCLE_SCALING(R.string.shortcut_action_cycle_scaling),
    CYCLE_EFFECT(R.string.shortcut_action_cycle_shader),
    TOGGLE_FF(R.string.shortcut_action_toggle_ff),
    HOLD_FF(R.string.shortcut_action_hold_ff),
    OPEN_GUIDE(R.string.shortcut_action_open_guide),
    OPEN_MENU(R.string.shortcut_action_open_menu)
}
