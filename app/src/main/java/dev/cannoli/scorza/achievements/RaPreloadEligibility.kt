package dev.cannoli.scorza.achievements

object RaPreloadEligibility {
    fun isEligible(platformTag: String?, raLoggedIn: Boolean): Boolean {
        if (!raLoggedIn) return false
        val tag = platformTag?.uppercase() ?: return false
        return RaConsoles.MAP.containsKey(tag)
    }
}
