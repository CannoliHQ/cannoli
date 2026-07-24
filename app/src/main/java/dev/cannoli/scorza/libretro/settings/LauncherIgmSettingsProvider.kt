package dev.cannoli.scorza.libretro.settings

import dev.cannoli.igm.GenericIgmSettingsItem
import dev.cannoli.igm.GenericIgmSettingsScreen
import dev.cannoli.igm.IgmSettingsExit
import dev.cannoli.igm.IgmSettingsProvider

class LauncherIgmSettingsProvider(
    private val host: LauncherSettingsHost,
    private val strings: LauncherSettingsStrings,
) : IgmSettingsProvider {

    private var onChanged: (() -> Unit)? = null

    override fun setOnChanged(callback: () -> Unit) { onChanged = callback }

    override fun screen(path: List<String>): GenericIgmSettingsScreen = when (path.firstOrNull()) {
        null -> root()
        "video" -> video()
        else -> GenericIgmSettingsScreen("", emptyList())
    }

    private fun root() = GenericIgmSettingsScreen(
        strings.rootTitle,
        listOf(
            GenericIgmSettingsItem.Category("video", strings.categoryVideo),
            GenericIgmSettingsItem.Category("emulator", strings.categoryEmulator),
            GenericIgmSettingsItem.Category("input", strings.categoryInput),
            GenericIgmSettingsItem.Category("advanced", strings.categoryAdvanced),
            GenericIgmSettingsItem.Action("info", strings.categoryInfo),
        ),
    )

    private fun video() = GenericIgmSettingsScreen(
        strings.categoryVideo,
        buildList {
            add(GenericIgmSettingsItem.Choice("video.scaling", strings.screenScaling, host.scalingLabel()))
            add(GenericIgmSettingsItem.Choice("video.sharpness", strings.screenSharpness, host.sharpnessLabel()))
            add(GenericIgmSettingsItem.Choice("video.shader", strings.shader, host.shaderLabel()))
            if (host.hasShaderParams) {
                add(GenericIgmSettingsItem.Action("video.shaderSettings", strings.shaderSettings))
            }
            add(GenericIgmSettingsItem.Choice("video.overlay", strings.overlay, host.overlayLabel()))
        },
    )

    override fun cycle(itemKey: String, direction: Int) {
        when (itemKey) {
            "video.scaling" -> host.cycleScaling(direction)
            "video.sharpness" -> host.cycleSharpness(direction)
            "video.shader" -> host.cycleShader(direction)
            "video.overlay" -> host.cycleOverlay(direction)
        }
    }

    override fun activate(itemKey: String) {
        when (itemKey) {
            "info" -> host.openInfo()
            "video.shaderSettings" -> host.openShaderSettings()
        }
    }

    override fun exitPrompt(): IgmSettingsExit =
        if (!host.settingsDirty()) IgmSettingsExit.Close
        else IgmSettingsExit.Prompt(
            title = null,
            options = listOf("Save for ${host.platformName}", "Save for this game", strings.discard),
        ) { choice ->
            when (choice) {
                0 -> host.saveToPlatform()
                1 -> host.saveToGame()
            }
        }
}
