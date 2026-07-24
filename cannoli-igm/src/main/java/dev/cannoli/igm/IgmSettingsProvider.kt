package dev.cannoli.igm

interface IgmSettingsProvider {
    fun screen(path: List<String>): GenericIgmSettingsScreen
    fun cycle(itemKey: String, direction: Int)
    fun activate(itemKey: String)
    fun exitPrompt(): IgmSettingsExit
    fun setOnChanged(callback: () -> Unit)
}
