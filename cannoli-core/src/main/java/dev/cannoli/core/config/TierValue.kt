package dev.cannoli.core.config

/**
 * What one scope says about a key in Cannoli's own override tier.
 *
 * [Off] masks the tier below; [Inherit] defers to it. Collapsing the two loses the ability to switch
 * something off at game scope without it coming back from the platform.
 */
sealed interface TierValue {

    data object Inherit : TierValue

    data object Off : TierValue

    data class Set(val value: String) : TierValue

    val chosen: String? get() = (this as? Set)?.value

    companion object {
        fun of(raw: String?): TierValue = when {
            raw == null -> Inherit
            raw.isBlank() -> Off
            else -> Set(raw)
        }

        /** Null removes the key, which is how [Inherit] is stored. */
        fun serialise(value: TierValue): String? = when (value) {
            is Inherit -> null
            is Off -> ""
            is Set -> value.value
        }
    }
}
