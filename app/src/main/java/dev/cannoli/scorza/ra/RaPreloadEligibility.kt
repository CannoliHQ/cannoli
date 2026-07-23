package dev.cannoli.scorza.ra

object RaPreloadEligibility {
    fun isEligible(
        platformTag: String?,
        embeddedCorePresent: Boolean,
        raLoggedIn: Boolean,
    ): Boolean {
        if (!embeddedCorePresent || !raLoggedIn) return false
        val tag = platformTag?.uppercase() ?: return false
        return RaConsoles.MAP.containsKey(tag)
    }
}
