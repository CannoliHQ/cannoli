package dev.cannoli.igm

sealed interface GenericIgmSettingsItem {
    val key: String
    val label: String

    data class Category(override val key: String, override val label: String) : GenericIgmSettingsItem
    data class Choice(
        override val key: String,
        override val label: String,
        val value: String,
        val hint: String? = null,
    ) : GenericIgmSettingsItem
    data class Action(override val key: String, override val label: String) : GenericIgmSettingsItem
}

data class GenericIgmSettingsScreen(val title: String, val items: List<GenericIgmSettingsItem>)

sealed interface IgmSettingsExit {
    data object Close : IgmSettingsExit
    data class Prompt(
        val title: String?,
        val options: List<String>,
        val onCancel: (() -> Unit)? = null,
        val onChoice: (Int) -> Unit,
    ) : IgmSettingsExit
}
