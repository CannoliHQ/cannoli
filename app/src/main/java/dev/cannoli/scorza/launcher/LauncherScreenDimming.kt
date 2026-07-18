package dev.cannoli.scorza.launcher

import android.view.Window
import android.view.WindowManager

internal fun shouldDimLauncherScreen(
    experimentalFeatures: Boolean,
    dualScreenLaunching: Boolean,
    dimLauncherDuringGames: Boolean,
    gameActive: Boolean,
    gameDisplayId: Int?,
    launcherDisplayId: Int,
): Boolean = experimentalFeatures &&
    dualScreenLaunching &&
    dimLauncherDuringGames &&
    gameActive &&
    gameDisplayId != null &&
    gameDisplayId != launcherDisplayId

/**
 * Keep the dimmed launcher touchable so it can consume taps without exposing Android's
 * secondary-display launcher, but prevent those taps from taking key focus from the game.
 */
internal fun setLauncherWindowInputBlocked(window: Window, blocked: Boolean) {
    if (blocked) {
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
    } else {
        window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
    }
}
