package dev.cannoli.scorza.ui.quickmenu

enum class QuickMenuRow {
    ROMM, SYNC_HISTORY, CONFLICTS, ERRORS, KITCHEN, RESCAN, INFO;

    companion object {
        fun visibleRows(rommPaired: Boolean, kitchenRunning: Boolean, saveSyncEnabled: Boolean = false, pendingConflicts: Int = 0, syncErrors: Int = 0): List<QuickMenuRow> =
            buildList {
                if (rommPaired) add(ROMM)
                if (rommPaired && saveSyncEnabled) add(SYNC_HISTORY)
                if (rommPaired && pendingConflicts > 0) add(CONFLICTS)
                if (rommPaired && saveSyncEnabled && syncErrors > 0) add(ERRORS)
                add(KITCHEN)
                add(RESCAN)
                add(INFO)
            }
    }
}
