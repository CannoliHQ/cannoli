package dev.cannoli.core.config

object OverrideTiers {

    // Basename of the core-independent override tiers, the sibling of the core-keyed <core>.cfg in
    // both directories below. Two places must agree on it: the launcher composes the tier into the
    // launch config, and the in-game menu writes it.
    const val SHARED = "cannoli"

    // The two override directories, named here for the same reason as SHARED: the launcher composes
    // these tiers and the in-game menu writes into them, and a path spelled out twice is a pair that
    // can drift into writing one place and reading another.
    const val SYSTEMS_DIR = "Config/Overrides/Systems"
    const val GAMES_DIR = "Config/Overrides/Games"

    // Cannoli's own keys in the shared tier, named here for the same reason. RetroArch has no
    // setting by either name, so nothing but these two ends catches a spelling drift: the menu
    // would stage a key the writer never matches and the save would forget the choice.
    const val KEY_OVERLAY = "cannoli_overlay"
    const val KEY_SHADER = "cannoli_shader"
}
