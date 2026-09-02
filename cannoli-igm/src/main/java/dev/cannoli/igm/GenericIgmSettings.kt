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
        val description: String? = null,
        /** A row that shows a value it does not own, so the legend must not offer to change it. */
        val readOnly: Boolean = false,
    ) : GenericIgmSettingsItem
    data class Action(override val key: String, override val label: String) : GenericIgmSettingsItem
}

data class GenericIgmSettingsScreen(val title: String, val items: List<GenericIgmSettingsItem>)

/** One answer to a prompt. The action rides with the label, so its position never means anything. */
data class IgmPromptOption(val label: String, val choose: () -> Unit)

sealed interface IgmSettingsExit {
    data object Close : IgmSettingsExit
    data class Prompt(
        val title: String?,
        val options: List<IgmPromptOption>,
        val onCancel: (() -> Unit)? = null,
    ) : IgmSettingsExit
}
