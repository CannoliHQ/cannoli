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
}
