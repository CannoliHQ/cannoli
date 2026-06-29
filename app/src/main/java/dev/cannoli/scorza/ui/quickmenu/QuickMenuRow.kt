package dev.cannoli.scorza.ui.quickmenu

enum class QuickMenuRow {
    ROMM, SYNC_HISTORY, CONFLICTS, KITCHEN, RESCAN, INFO;

    companion object {
        fun visibleRows(rommPaired: Boolean, kitchenRunning: Boolean, saveSyncEnabled: Boolean = false, pendingConflicts: Int = 0): List<QuickMenuRow> =
            buildList {
                if (rommPaired) add(ROMM)
                if (rommPaired && saveSyncEnabled) add(SYNC_HISTORY)
                if (rommPaired && pendingConflicts > 0) add(CONFLICTS)
                add(KITCHEN)
                add(RESCAN)
                add(INFO)
            }
    }
}
