package dev.cannoli.core

/**
 * The RetroAchievements session, account and mode keys.
 *
 * These are injected fresh into the per-launch RetroArch config on every launch and must never
 * survive in any other config on disk. Kept as the single source of truth for the two guards that
 * enforce that: the boot migration that strips them from persisted configs, and the IGM save path
 * that drops them before writing an override. A stale copy of any of these (a per-game override
 * re-enabling hardcore against a forced softcore, for one) could otherwise clobber the fresh value.
 */
object CheevosSessionKeys {
    val ALL: Set<String> = setOf(
        "cheevos_enable",
        "cheevos_hardcore_mode_enable",
        "cheevos_username",
        "cheevos_token",
        "cheevos_password",
    )
}
