package dev.cannoli.scorza.libretro.settings

import dev.cannoli.scorza.libretro.LibretroRunner

interface LauncherSettingsHost {
    val hasShaderParams: Boolean
    val maxFfSpeed: Int
    val showFpsBaseline: Boolean
    val debugHud: Boolean
    val leftStickAsDpad: Boolean
    val allowDiagonals: Boolean
    val experimentalFeatures: Boolean
    val coreOptions: List<LibretroRunner.CoreOption>
    val coreCategories: List<LibretroRunner.CoreOptionCategory>
    val controllerTypes: List<LibretroRunner.ControllerType>
    val occupiedPorts: List<Int>
    val platformName: String

    fun scalingLabel(): String
    fun sharpnessLabel(): String
    fun shaderLabel(): String
    fun overlayLabel(): String
    fun deviceTypeLabel(port: Int): String
    fun cycleScaling(direction: Int)
    fun cycleSharpness(direction: Int)
    fun cycleShader(direction: Int)
    fun cycleOverlay(direction: Int)
    fun cycleFfSpeed(direction: Int)
    fun toggleShowFps()
    fun toggleDebugHud()
    fun toggleLeftStickAsDpad()
    fun toggleDpadMode()
    fun cyclePortDeviceType(port: Int, direction: Int)
    fun cycleCoreOption(optionKey: String, direction: Int)

    fun openButtonMappings()
    fun openShortcuts()
    fun openShaderSettings()
    fun openInfo()

    fun settingsDirty(): Boolean
    fun saveToPlatform()
    fun saveToGame()
}
