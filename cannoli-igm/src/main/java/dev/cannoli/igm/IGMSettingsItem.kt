package dev.cannoli.igm

data class IGMSettingsItem(
    val label: String,
    val value: String? = null,
    val hint: String? = null,
    val description: String? = null,
    /** False keeps Left and Right out of the legend, for a row that only reports something. */
    val cyclable: Boolean = true,
)
