package dev.cannoli.scorza.ra

object RaPreloadEligibility {
    /**
     * Achievements need a runner that can report emulated console memory. The internal libretro
     * runner provided that and has been removed; the port onto RetroArch's own rcheevos will
     * restore it. Until then nothing is eligible, so the preload rows stay hidden rather than
     * caching set data that nothing can read. Flip this when the port lands.
     *
     * The preserved runner-side implementation is in `reference/retroachievements/`.
     */
    const val RUNNER_SUPPORTS_ACHIEVEMENTS = false

    fun isEligible(
        platformTag: String?,
        raLoggedIn: Boolean,
        runnerSupportsAchievements: Boolean = RUNNER_SUPPORTS_ACHIEVEMENTS,
    ): Boolean {
        if (!runnerSupportsAchievements || !raLoggedIn) return false
        val tag = platformTag?.uppercase() ?: return false
        return RaConsoles.MAP.containsKey(tag)
    }
}
