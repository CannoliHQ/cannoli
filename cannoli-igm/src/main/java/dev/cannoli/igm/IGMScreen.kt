package dev.cannoli.igm

sealed class IGMScreen {
    abstract val selectedIndex: Int

    data class Menu(override val selectedIndex: Int = 0, val confirmDeleteSlot: Boolean = false) : IGMScreen()
    data class ProviderSettings(
        override val selectedIndex: Int = 0,
        val path: List<String> = emptyList(),
        val title: String = "",
    ) : IGMScreen()
    data class SettingsExitPrompt(override val selectedIndex: Int = 0) : IGMScreen()
    data class Achievements(override val selectedIndex: Int = 0, val achievements: List<AchievementInfo> = emptyList(), val filter: Int = 0, val status: String = "") : IGMScreen()
    data class AchievementDetail(override val selectedIndex: Int = 0, val achievement: AchievementInfo, val parentIndex: Int = 0) : IGMScreen()
    data class GuidePicker(override val selectedIndex: Int = 0) : IGMScreen()
    data class Guide(override val selectedIndex: Int = 0, val filePath: String, val page: Int = 0, val textZoom: Int = 1) : IGMScreen()
    /** [selectedIndex] is -1 when the open file has nothing that can be toggled. */
    data class Cheats(override val selectedIndex: Int = 0) : IGMScreen()
}
