package dev.cannoli.scorza.achievements

object RaPreloadEligibility {
    /**
     * Whether a game can have its achievement set cached ahead of playing it.
     *
     * Two conditions, both real: the set is fetched from the user's account, and RetroAchievements
     * only publishes for the consoles [RaConsoles.MAP] names. A platform absent from that map has
     * no sets to cache whatever else is true.
     */
    fun isEligible(platformTag: String?, raLoggedIn: Boolean): Boolean {
        if (!raLoggedIn) return false
        val tag = platformTag?.uppercase() ?: return false
        return RaConsoles.MAP.containsKey(tag)
    }
}
