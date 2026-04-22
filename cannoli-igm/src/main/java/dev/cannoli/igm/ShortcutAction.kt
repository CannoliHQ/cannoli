package dev.cannoli.igm

enum class ShortcutAction(val label: String) {
    SAVE_STATE("Save State"),
    LOAD_STATE("Load State"),
    RESET_GAME("Reset Game"),
    SAVE_AND_QUIT("Save and Quit"),
    CYCLE_SCALING("Cycle Scaling"),
    CYCLE_EFFECT("Cycle Shader"),
    TOGGLE_FF("Toggle Fast Forward"),
    HOLD_FF("Hold Fast Forward"),
    OPEN_GUIDE("Open Guide"),
    OPEN_MENU("Open Menu")
}
