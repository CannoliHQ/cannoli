package dev.cannoli.igm

interface IgmSettingsProvider {
    fun screen(path: List<String>): GenericIgmSettingsScreen
    fun cycle(itemKey: String, direction: Int)
    fun activate(itemKey: String): IgmSettingsExit.Prompt?
    fun exitPrompt(): IgmSettingsExit
    fun setOnChanged(callback: () -> Unit)

    fun markChangedExternally(keys: Set<String>) {}

    fun canRestoreDefault(path: List<String>): Boolean = false

    fun restoreDefault(path: List<String>): Set<String> = emptySet()

    fun canReset(path: List<String>): Boolean = false

    fun resetPrompt(path: List<String>): IgmSettingsExit.Prompt? = null

    fun applyPendingChanges() {}

    fun canReorder(path: List<String>, index: Int): Boolean = false

    fun reorder(path: List<String>, index: Int, delta: Int): Int = index

    fun canRemoveRow(path: List<String>, index: Int): Boolean = false

    fun removeRow(path: List<String>, index: Int) {}

    fun returnPathAfter(itemKey: String, path: List<String>): List<String>? = null
}
