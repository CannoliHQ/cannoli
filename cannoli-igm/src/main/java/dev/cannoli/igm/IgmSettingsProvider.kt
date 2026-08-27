package dev.cannoli.igm

interface IgmSettingsProvider {
    fun screen(path: List<String>): GenericIgmSettingsScreen
    fun cycle(itemKey: String, direction: Int)
    fun activate(itemKey: String): IgmSettingsExit.Prompt?
    fun exitPrompt(): IgmSettingsExit
    fun setOnChanged(callback: () -> Unit)

    /**
     * Records keys Cannoli applied outside the settings tree so they join the save prompt on the
     * way out. A tree that has no such surface leaves this alone.
     */
    fun markChangedExternally(keys: Set<String>) {}
}
