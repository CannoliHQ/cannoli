package dev.cannoli.scorza.libretro.settings

import dev.cannoli.scorza.libretro.LibretroRunner

fun fakeControllerType(id: Int) = LibretroRunner.ControllerType("Type $id", id)

fun fakeCategory(key: String, desc: String) =
    LibretroRunner.CoreOptionCategory(key, desc, "")

fun fakeOption(
    key: String,
    desc: String,
    selected: String,
    values: List<Pair<String, String>>,
    category: String,
    info: String = "",
) = LibretroRunner.CoreOption(
    key = key,
    desc = desc,
    values = values.map { LibretroRunner.CoreOptionValue(it.first, it.second) },
    selected = selected,
    category = category,
    info = info,
)

open class FakeLauncherSettingsHost : LauncherSettingsHost {
    override var hasShaderParams = false
    override var maxFfSpeed = 4
    override var showFpsBaseline = false
    override var debugHud = false
    override var leftStickAsDpad = false
    override var allowDiagonals = true
    override var experimentalFeatures = false
    override var coreOptions = emptyList<LibretroRunner.CoreOption>()
    override var coreCategories = emptyList<LibretroRunner.CoreOptionCategory>()
    override var controllerTypes = emptyList<LibretroRunner.ControllerType>()
    override var occupiedPorts = listOf(0)
    override var platformName = "SNES"
    var dirty = false

    val calls = mutableListOf<String>()

    var scaling = "Core Reported"
    var sharp = "Sharp"
    var shader = "Off"
    var overlayName = "None"

    override fun scalingLabel() = scaling
    override fun sharpnessLabel() = sharp
    override fun shaderLabel() = shader
    override fun overlayLabel() = overlayName
    override fun deviceTypeLabel(port: Int) = "Gamepad"
    override fun cycleScaling(direction: Int) { calls.add("scaling:$direction") }
    override fun cycleSharpness(direction: Int) { calls.add("sharpness:$direction") }
    override fun cycleShader(direction: Int) { calls.add("shader:$direction") }
    override fun cycleOverlay(direction: Int) { calls.add("overlay:$direction") }
    override fun cycleFfSpeed(direction: Int) { calls.add("ff:$direction") }
    override fun toggleShowFps() { calls.add("showFps"); showFpsBaseline = !showFpsBaseline }
    override fun toggleDebugHud() { calls.add("debugHud"); debugHud = !debugHud }
    override fun toggleLeftStickAsDpad() { calls.add("leftStick"); leftStickAsDpad = !leftStickAsDpad }
    override fun toggleDpadMode() { calls.add("dpadMode"); allowDiagonals = !allowDiagonals }
    override fun cyclePortDeviceType(port: Int, direction: Int) { calls.add("port$port:$direction") }
    override fun cycleCoreOption(optionKey: String, direction: Int) { calls.add("core:$optionKey:$direction") }
    override fun openButtonMappings() { calls.add("openButtons") }
    override fun openShortcuts() { calls.add("openShortcuts") }
    override fun openShaderSettings() { calls.add("openShaderSettings") }
    override fun openInfo() { calls.add("openInfo") }
    override fun settingsDirty() = dirty
    override fun saveToPlatform() { calls.add("savePlatform") }
    override fun saveToGame() { calls.add("saveGame") }
}
