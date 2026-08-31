package dev.cannoli.scorza.ui.quickmenu

enum class QuickMenuRow {
    SETTINGS, ROMM, DOWNLOADS, SYNC_HISTORY, CONFLICTS, ERRORS, KITCHEN, RESCAN, INFO, ABOUT, DEBUG;

    companion object {
        /**
         * [downloadCount] includes rows that have finished, and the queue leads the menu for as long
         * as it holds any of them. It used to lead only while something was still transferring and
         * then drop to sit after ROMM, which moved a row under the thumb of anyone who opened the
         * menu to check on a download that had just landed. It now stays put until the list is
         * cleared, which is the one action that makes it go away.
         */
        fun visibleRows(rommPaired: Boolean, kitchenRunning: Boolean, saveSyncEnabled: Boolean = false, pendingConflicts: Int = 0, syncErrors: Int = 0, downloadCount: Int = 0, debugBuild: Boolean = false): List<QuickMenuRow> =
            buildList {
                if (downloadCount > 0) add(DOWNLOADS)
                if (rommPaired && pendingConflicts > 0) add(CONFLICTS)
                if (rommPaired && saveSyncEnabled && syncErrors > 0) add(ERRORS)
                add(SETTINGS)
                if (rommPaired) add(ROMM)
                if (rommPaired && saveSyncEnabled) add(SYNC_HISTORY)
                add(KITCHEN)
                add(RESCAN)
                add(INFO)
                add(ABOUT)
                if (debugBuild) add(DEBUG)
            }
    }
}
