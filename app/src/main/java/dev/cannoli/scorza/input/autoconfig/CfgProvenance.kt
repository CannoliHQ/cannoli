package dev.cannoli.scorza.input.autoconfig

enum class CfgProvenance {
    INPUT_DB,
    USER;

    companion object {
        fun parse(value: String): CfgProvenance? =
            entries.firstOrNull { it.name.equals(value.trim(), ignoreCase = true) }
    }
}
