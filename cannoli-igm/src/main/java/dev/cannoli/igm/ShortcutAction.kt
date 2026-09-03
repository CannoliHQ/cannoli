package dev.cannoli.igm

import androidx.annotation.StringRes
import dev.cannoli.ui.R

/**
 * The in-game actions a chord can be bound to.
 *
 * [holdMs] is how long the chord must stay down before the action runs, 0 for the usual case of
 * running the moment the chord completes. Only an action you would regret firing by accident earns
 * one. The chord's keys are taken from the game as soon as it matches either way, so the wait costs
 * the game nothing; what it buys is a chance to let go.
 *
 * CYCLE_SCALING was retired on 2026-08-31: v2 answers it better through Screen Geometry and the
 * aspect rows in All Settings, where the value is visible while it changes, and a blind cycle
 * through RetroArch's aspect table mostly lands somewhere nobody wants. A stale entry in
 * shortcuts.ini for it is skipped rather than failing the file.
 */
enum class ShortcutAction(@StringRes val labelRes: Int, val holdMs: Int = 0) {
    SAVE_STATE(R.string.shortcut_action_save_state),
    LOAD_STATE(R.string.shortcut_action_load_state),
    RESET_GAME(R.string.shortcut_action_reset_game),
    SAVE_AND_QUIT(R.string.shortcut_action_save_and_quit),
    SAVE_AND_QUIT_HOLD(R.string.shortcut_action_save_and_quit_hold, holdMs = 1250),
    CYCLE_EFFECT(R.string.shortcut_action_cycle_shader),
    TOGGLE_SHOW_FPS(R.string.shortcut_action_toggle_show_fps),
    TOGGLE_FF(R.string.shortcut_action_toggle_ff),
    HOLD_FF(R.string.shortcut_action_hold_ff),
    REWIND(R.string.shortcut_action_rewind),
    OPEN_GUIDE(R.string.shortcut_action_open_guide),
    OPEN_MENU(R.string.shortcut_action_open_menu)
}
