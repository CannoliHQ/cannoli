package dev.cannoli.igm

data class IGMSettingsItem(
    val label: String,
    val value: String? = null,
    val hint: String? = null,
    val description: String? = null,
    val cyclable: Boolean = true,
    val leadingIcon: String? = null,
)
