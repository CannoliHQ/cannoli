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

    /**
     * Whether this level holds a choice the game overrides, and so has one worth offering to drop.
     *
     * Drives the legend, which makes the offer double as the answer to where the value came from:
     * the action appears only when the game is the one deciding.
     */
    fun canRestoreDefault(path: List<String>): Boolean = false

    /** Drops that override and applies what the platform says instead. */
    fun restoreDefault(path: List<String>): Set<String> = emptySet()
}
