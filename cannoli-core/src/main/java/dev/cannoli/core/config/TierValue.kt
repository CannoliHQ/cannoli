package dev.cannoli.core.config

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

        fun serialise(value: TierValue): String? = when (value) {
            is Inherit -> null
            is Off -> ""
            is Set -> value.value
        }
    }
}
