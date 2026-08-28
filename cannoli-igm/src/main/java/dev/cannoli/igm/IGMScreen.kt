package dev.cannoli.igm

sealed class IGMScreen {
    abstract val selectedIndex: Int

    data class Menu(override val selectedIndex: Int = 0, val confirmDeleteSlot: Boolean = false) : IGMScreen()
    data class ProviderSettings(
        override val selectedIndex: Int = 0,
        val path: List<String> = emptyList(),
        val title: String = "",
        // RetroArch's explanation of the highlighted row, shown instead of the list while set.
        val description: String? = null,
        val descriptionScroll: Int = 0,
    ) : IGMScreen()
    data class SettingsExitPrompt(override val selectedIndex: Int = 0) : IGMScreen()
    /**
     * Cannoli's live preview picker. [selectedIndex] indexes the asset list.
     *
     * [unwindOnBack] says whether leaving must also step the settings navigator up a level. Entering
     * through a category pushed one, so it must; arriving from a row that fired an action did not,
     * and unwinding then would drop the browser above the folder it was showing.
     */
    data class PreviewPicker(
        override val selectedIndex: Int = 0,
        val unwindOnBack: Boolean = false,
    ) : IGMScreen()
    /**
     * Naming a shader preset. [selectedIndex] is unused: the keyboard carries its own cursor.
     *
     * [help] shows the keyboard's own button reference, which its legend offers and which would
     * otherwise advertise something this screen does not answer.
     */
    data class ShaderSaveName(
        override val selectedIndex: Int = 0,
        val keyboard: dev.cannoli.ui.components.KeyboardState =
            dev.cannoli.ui.components.KeyboardState(),
        val help: Boolean = false,
    ) : IGMScreen()
    data class Achievements(override val selectedIndex: Int = 0, val achievements: List<AchievementInfo> = emptyList(), val filter: Int = 0, val status: String = "") : IGMScreen()
    data class AchievementDetail(override val selectedIndex: Int = 0, val achievement: AchievementInfo, val parentIndex: Int = 0) : IGMScreen()
    data class GuidePicker(override val selectedIndex: Int = 0) : IGMScreen()
    data class Guide(override val selectedIndex: Int = 0, val filePath: String, val page: Int = 0, val textZoom: Int = 1) : IGMScreen()
    /** [selectedIndex] is -1 when the open file has nothing that can be toggled. */
    data class Cheats(override val selectedIndex: Int = 0) : IGMScreen()
    data class CheatsHardcoreWarning(
        override val selectedIndex: Int = 0,
        val pendingRowIndex: Int,
    ) : IGMScreen()
}
