package dev.cannoli.igm

class InGameMenuOptions(
    hasDiscs: Boolean,
    val discLabel: String,
    hasAchievements: Boolean = false,
    hasGuides: Boolean = false,
    quitLabel: String = "Quit"
) {
    val options: List<String>
    val resumeIndex = 0
    val saveStateIndex = 1
    val loadStateIndex = 2
    val achievementsIndex: Int
    val guideIndex: Int
    val settingsIndex: Int
    val switchDiscIndex: Int
    val resetIndex: Int
    val quitIndex: Int

    init {
        val list = mutableListOf("Resume", "Save State", "Load State")
        if (hasAchievements) {
            achievementsIndex = list.size
            list.add("Achievements")
        } else {
            achievementsIndex = -1
        }
        if (hasGuides) {
            guideIndex = list.size
            list.add("Guide")
        } else {
            guideIndex = -1
        }
        settingsIndex = list.size
        list.add("Settings")
        if (hasDiscs) {
            switchDiscIndex = list.size
            list.add("Switch Disc")
        } else {
            switchDiscIndex = -1
        }
        resetIndex = list.size
        list.add("Reset")
        quitIndex = list.size
        list.add(quitLabel)
        options = list
    }
}
