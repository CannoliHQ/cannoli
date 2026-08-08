package dev.cannoli.scorza.ui.quickmenu

enum class QuickMenuRow {
    SETTINGS, ROMM, DOWNLOADS, SYNC_HISTORY, CONFLICTS, ERRORS, KITCHEN, RESCAN, INFO, ABOUT, DEBUG;

    companion object {
        fun visibleRows(rommPaired: Boolean, kitchenRunning: Boolean, saveSyncEnabled: Boolean = false, pendingConflicts: Int = 0, syncErrors: Int = 0, downloadCount: Int = 0, debugBuild: Boolean = false): List<QuickMenuRow> =
            buildList {
                add(SETTINGS)
                if (rommPaired) add(ROMM)
                if (downloadCount > 0) add(DOWNLOADS)
                if (rommPaired && saveSyncEnabled) add(SYNC_HISTORY)
                if (rommPaired && pendingConflicts > 0) add(CONFLICTS)
                if (rommPaired && saveSyncEnabled && syncErrors > 0) add(ERRORS)
                add(KITCHEN)
                add(RESCAN)
                add(INFO)
                add(ABOUT)
                if (debugBuild) add(DEBUG)
            }
    }
}
