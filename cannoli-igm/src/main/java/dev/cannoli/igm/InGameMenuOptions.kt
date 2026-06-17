package dev.cannoli.igm

enum class IgmMenuAction { RESUME, SAVE_STATE, LOAD_STATE, ACHIEVEMENTS, GUIDE, SETTINGS, SWITCH_DISC, REASSIGN, RESET, QUIT }

class InGameMenuOptions(
    hasDiscs: Boolean,
    val discLabel: String,
    hasAchievements: Boolean = false,
    val hasGuides: Boolean = false,
    hasReassign: Boolean = false,
    quitLabel: String = "Quit",
) {
    val actions: List<IgmMenuAction> = buildList {
        add(IgmMenuAction.RESUME)
        add(IgmMenuAction.SAVE_STATE)
        add(IgmMenuAction.LOAD_STATE)
        if (hasAchievements) add(IgmMenuAction.ACHIEVEMENTS)
        if (hasGuides) add(IgmMenuAction.GUIDE)
        add(IgmMenuAction.SETTINGS)
        if (hasDiscs) add(IgmMenuAction.SWITCH_DISC)
        if (hasReassign) add(IgmMenuAction.REASSIGN)
        add(IgmMenuAction.RESET)
        add(IgmMenuAction.QUIT)
    }

    val options: List<String> = actions.map { action ->
        when (action) {
            IgmMenuAction.RESUME -> "Resume"
            IgmMenuAction.SAVE_STATE -> "Save State"
            IgmMenuAction.LOAD_STATE -> "Load State"
            IgmMenuAction.ACHIEVEMENTS -> "Achievements"
            IgmMenuAction.GUIDE -> "Guide"
            IgmMenuAction.SETTINGS -> "Settings"
            IgmMenuAction.SWITCH_DISC -> discLabel
            IgmMenuAction.REASSIGN -> "Reassign Players"
            IgmMenuAction.RESET -> "Reset"
            IgmMenuAction.QUIT -> quitLabel
        }
    }

    val resumeIndex get() = actions.indexOf(IgmMenuAction.RESUME)
    val saveStateIndex get() = actions.indexOf(IgmMenuAction.SAVE_STATE)
    val loadStateIndex get() = actions.indexOf(IgmMenuAction.LOAD_STATE)
    val achievementsIndex get() = actions.indexOf(IgmMenuAction.ACHIEVEMENTS)
    val guideIndex get() = actions.indexOf(IgmMenuAction.GUIDE)
    val settingsIndex get() = actions.indexOf(IgmMenuAction.SETTINGS)
    val switchDiscIndex get() = actions.indexOf(IgmMenuAction.SWITCH_DISC)
    val reassignIndex get() = actions.indexOf(IgmMenuAction.REASSIGN)
    val resetIndex get() = actions.indexOf(IgmMenuAction.RESET)
    val quitIndex get() = actions.indexOf(IgmMenuAction.QUIT)

    fun actionAt(index: Int): IgmMenuAction? = actions.getOrNull(index)
}
