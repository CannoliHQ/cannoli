package dev.cannoli.scorza.ui.quickmenu

enum class QuickMenuRow {
    SETTINGS, ROMM, DOWNLOADS, SYNC_HISTORY, CONFLICTS, ERRORS, KITCHEN, RESCAN, INFO, ABOUT, DEBUG;

    companion object {
        /**
         * [activeDownloads] counts only what is still queued or transferring, while [downloadCount]
         * includes rows that have finished. Work in flight goes to the top, because that is what the
         * menu was opened to check on; once everything has landed the row stays put in its usual
         * place, still reachable to clear the list.
         */
        fun visibleRows(rommPaired: Boolean, kitchenRunning: Boolean, saveSyncEnabled: Boolean = false, pendingConflicts: Int = 0, syncErrors: Int = 0, downloadCount: Int = 0, activeDownloads: Int = 0, debugBuild: Boolean = false): List<QuickMenuRow> =
            buildList {
                if (activeDownloads > 0) add(DOWNLOADS)
                if (rommPaired && pendingConflicts > 0) add(CONFLICTS)
                if (rommPaired && saveSyncEnabled && syncErrors > 0) add(ERRORS)
                add(SETTINGS)
                if (rommPaired) add(ROMM)
                if (downloadCount > 0 && activeDownloads == 0) add(DOWNLOADS)
                if (rommPaired && saveSyncEnabled) add(SYNC_HISTORY)
                add(KITCHEN)
                add(RESCAN)
                add(INFO)
                add(ABOUT)
                if (debugBuild) add(DEBUG)
            }
    }
}
