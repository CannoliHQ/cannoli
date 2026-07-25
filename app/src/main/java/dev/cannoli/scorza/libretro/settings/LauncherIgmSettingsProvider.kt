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
        "advanced" -> advanced()
        "input" -> input()
        "emulator" -> if (path.size == 1) emulator() else emulatorCategory(path[1])
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

    private fun advanced() = GenericIgmSettingsScreen(
        strings.categoryAdvanced,
        buildList {
            if (host.controllerTypes.size > 1) {
                val ports = host.occupiedPorts
                if (ports.size <= 1) {
                    add(GenericIgmSettingsItem.Choice(
                        "advanced.controller.0", strings.controllerType, host.deviceTypeLabel(0)))
                } else {
                    for (p in ports) add(GenericIgmSettingsItem.Choice(
                        "advanced.controller.$p", "P${p + 1} Controller", host.deviceTypeLabel(p)))
                }
            }
            add(GenericIgmSettingsItem.Choice("advanced.ffSpeed", strings.maxFfSpeed, "${host.maxFfSpeed}x"))
            add(GenericIgmSettingsItem.Choice(
                "advanced.showFps", strings.showFps, if (host.showFpsBaseline) strings.on else strings.off))
            add(GenericIgmSettingsItem.Choice(
                "advanced.debugHud", strings.debugHud, if (host.debugHud) strings.on else strings.off))
        },
    )

    private fun input() = GenericIgmSettingsScreen(
        strings.categoryInput,
        buildList {
            add(GenericIgmSettingsItem.Action("input.buttons", strings.buttonMappings))
            add(GenericIgmSettingsItem.Action("input.shortcuts", strings.shortcuts))
            add(GenericIgmSettingsItem.Choice(
                "input.leftStick", strings.leftStickDpad,
                if (host.leftStickAsDpad) strings.on else strings.off))
            if (host.experimentalFeatures) {
                add(GenericIgmSettingsItem.Choice(
                    "input.dpadMode", strings.dpadMode,
                    if (host.allowDiagonals) strings.dpad8Way else strings.dpad4Way))
            }
        },
    )

    private fun hasCategories(): Boolean =
        host.coreCategories.isNotEmpty() && host.coreOptions.any { it.category.isNotEmpty() }

    private fun emulator(): GenericIgmSettingsScreen {
        val items: List<GenericIgmSettingsItem> = when {
            hasCategories() -> buildList {
                val used = host.coreCategories.filter { cat -> host.coreOptions.any { it.category == cat.key } }
                for (cat in used) {
                    add(GenericIgmSettingsItem.Category(cat.key, cat.desc))
                }
                if (host.coreOptions.any { it.category.isEmpty() }) {
                    add(GenericIgmSettingsItem.Category("", strings.other))
                }
            }
            host.coreOptions.isEmpty() -> listOf(GenericIgmSettingsItem.Action("core.none", strings.noOptions))
            else -> host.coreOptions.map(::optionRow)
        }
        return GenericIgmSettingsScreen(strings.categoryEmulator, items)
    }

    private fun emulatorCategory(categoryKey: String) = GenericIgmSettingsScreen(
        host.coreCategories.firstOrNull { it.key == categoryKey }?.desc ?: strings.other,
        host.coreOptions.filter { it.category == categoryKey }.map(::optionRow),
    )

    private fun optionRow(opt: dev.cannoli.scorza.libretro.LibretroRunner.CoreOption) = GenericIgmSettingsItem.Choice(
        key = "core.${opt.key}",
        label = opt.desc,
        value = opt.values.find { it.value == opt.selected }?.label ?: opt.selected,
        hint = opt.info.ifEmpty { null },
    )

    override fun cycle(itemKey: String, direction: Int) {
        when (itemKey) {
            "video.scaling" -> host.cycleScaling(direction)
            "video.sharpness" -> host.cycleSharpness(direction)
            "video.shader" -> host.cycleShader(direction)
            "video.overlay" -> host.cycleOverlay(direction)
            "advanced.ffSpeed" -> host.cycleFfSpeed(direction)
            "advanced.showFps" -> host.toggleShowFps()
            "advanced.debugHud" -> host.toggleDebugHud()
            "input.leftStick" -> host.toggleLeftStickAsDpad()
            "input.dpadMode" -> host.toggleDpadMode()
            else -> when {
                itemKey.startsWith("core.") -> host.cycleCoreOption(itemKey.removePrefix("core."), direction)
                itemKey.startsWith("advanced.controller.") ->
                    host.cyclePortDeviceType(itemKey.substringAfterLast('.').toInt(), direction)
            }
        }
    }

    override fun activate(itemKey: String): IgmSettingsExit.Prompt? {
        when (itemKey) {
            "info" -> host.openInfo()
            "video.shaderSettings" -> host.openShaderSettings()
            "input.buttons" -> host.openButtonMappings()
            "input.shortcuts" -> host.openShortcuts()
        }
        return null
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
