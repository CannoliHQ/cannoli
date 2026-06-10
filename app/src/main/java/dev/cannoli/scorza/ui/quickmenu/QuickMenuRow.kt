package dev.cannoli.scorza.ui.quickmenu

enum class QuickMenuRow {
    ROMM, KITCHEN, RESCAN, INFO;

    companion object {
        fun visibleRows(rommPaired: Boolean, kitchenRunning: Boolean): List<QuickMenuRow> =
            buildList {
                if (rommPaired) add(ROMM)
                add(KITCHEN)
                add(RESCAN)
                add(INFO)
            }
    }
}
