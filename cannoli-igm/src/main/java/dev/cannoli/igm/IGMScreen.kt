package dev.cannoli.igm

sealed class IGMScreen {
    abstract val selectedIndex: Int

    data class Menu(override val selectedIndex: Int = 0, val confirmDeleteSlot: Boolean = false) : IGMScreen()
    data class ProviderSettings(
        override val selectedIndex: Int = 0,
        val path: List<String> = emptyList(),
        val title: String = "",
        val description: String? = null,
        val descriptionScroll: Int = 0,
    ) : IGMScreen()
    data class SettingsExitPrompt(
        override val selectedIndex: Int = 0,
        val title: String? = null,
    ) : IGMScreen()
    data class PreviewPicker(
        override val selectedIndex: Int = 0,
        val unwindOnBack: Boolean = false,
    ) : IGMScreen()
    data class ShaderSaveName(
        override val selectedIndex: Int = 0,
        val keyboard: dev.cannoli.ui.components.KeyboardState =
            dev.cannoli.ui.components.KeyboardState(),
        val help: Boolean = false,
    ) : IGMScreen()
    data class Achievements(override val selectedIndex: Int = 0, val achievements: List<AchievementInfo> = emptyList(), val filter: Int = 0, val status: String = "") : IGMScreen()
    data class AchievementDetail(override val selectedIndex: Int = 0, val achievement: AchievementInfo, val parentIndex: Int = 0) : IGMScreen()
    data class Shortcuts(
        override val selectedIndex: Int = 0,
        val listening: Boolean = false,
        val heldKeys: Set<Int> = emptySet(),
        val countdownMs: Int = 0,
    ) : IGMScreen()

    data class GuidePicker(override val selectedIndex: Int = 0) : IGMScreen()
    data class Guide(override val selectedIndex: Int = 0, val filePath: String, val page: Int = 0, val textZoom: Int = 1, val help: Boolean = false) : IGMScreen()
    data class Cheats(override val selectedIndex: Int = 0) : IGMScreen()
    data class CheatsHardcoreWarning(
        override val selectedIndex: Int = 0,
        val pendingRowIndex: Int,
    ) : IGMScreen()
    data class ReassignPlayers(override val selectedIndex: Int = 0, val marked: Int? = null) : IGMScreen()

    data class ButtonMappings(
        override val selectedIndex: Int = 0,
        val listening: Boolean = false,
    ) : IGMScreen()
}
