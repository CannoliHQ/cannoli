package dev.cannoli.scorza.ui.quickmenu

enum class QuickMenuRow {
    SETTINGS, ROMM, DOWNLOADS, SYNC_HISTORY, CONFLICTS, ERRORS, UNSYNCED_UNLOCKS, KITCHEN, RESCAN, INFO, ABOUT, DEBUG;

    companion object {
        fun visibleRows(rommPaired: Boolean, kitchenRunning: Boolean, saveSyncEnabled: Boolean = false, pendingConflicts: Int = 0, syncErrors: Int = 0, pendingUnlocks: Int = 0, downloadCount: Int = 0, devBuild: Boolean = false): List<QuickMenuRow> =
            buildList {
                if (downloadCount > 0) add(DOWNLOADS)
                if (rommPaired && pendingConflicts > 0) add(CONFLICTS)
                if (rommPaired && saveSyncEnabled && syncErrors > 0) add(ERRORS)
                if (pendingUnlocks > 0) add(UNSYNCED_UNLOCKS)
                add(SETTINGS)
                if (rommPaired) add(ROMM)
                if (rommPaired && saveSyncEnabled) add(SYNC_HISTORY)
                add(KITCHEN)
                add(RESCAN)
                add(INFO)
                add(ABOUT)
                if (devBuild) add(DEBUG)
            }
    }
}
