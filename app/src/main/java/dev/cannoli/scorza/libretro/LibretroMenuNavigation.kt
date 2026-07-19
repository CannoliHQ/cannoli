package dev.cannoli.scorza.libretro

/**
 * The menu poller reads controller state shared with the launcher. Keep it dormant during
 * gameplay so a held D-pad cannot navigate the launcher on another display.
 */
internal fun shouldPollInGameMenuNavigation(
    hasMenuScreens: Boolean,
    activityResumed: Boolean,
): Boolean = hasMenuScreens && activityResumed
