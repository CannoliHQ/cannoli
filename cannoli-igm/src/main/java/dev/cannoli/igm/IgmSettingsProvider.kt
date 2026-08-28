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

    /**
     * Compiles anything built but not yet compiled, called when the screen that built it is left.
     *
     * Not a button, because there is no moment before this one where pressing it would show you
     * anything: the menu has the game paused and a paused RetroArch presents no frames, so a chain
     * becomes visible when the menu goes away and not before.
     */
    fun applyPendingChanges() {}

    /**
     * Whether the row at [index] can be dragged within its list.
     *
     * Reordering a shader chain is the same act as reordering the platform list, so it is the same
     * interaction: Select picks the row up, Up and Down move it, Confirm puts it down.
     */
    fun canReorder(path: List<String>, index: Int): Boolean = false

    /** Moves that row by [delta], returning where it ended up so the highlight can follow it. */
    fun reorder(path: List<String>, index: Int, delta: Int): Int = index

    /** Whether the row at [index] is something the list can take away. */
    fun canRemoveRow(path: List<String>, index: Int): Boolean = false

    fun removeRow(path: List<String>, index: Int) {}

    /**
     * Where the navigator should sit once [itemKey] has fired, or null to stay where it is.
     *
     * A browser that exists to answer one question is done once it is answered: picking a shader
     * for the chain has no reason to leave you three levels deep in the folder you found it in.
     */
    fun returnPathAfter(itemKey: String, path: List<String>): List<String>? = null
}
