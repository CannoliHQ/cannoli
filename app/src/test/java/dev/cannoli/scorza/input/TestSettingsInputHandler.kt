package dev.cannoli.scorza.input

import android.content.Context
import dev.cannoli.scorza.input.screen.SettingsInputHandler
import dev.cannoli.scorza.navigation.NavigationController
import dev.cannoli.scorza.ui.viewmodel.SettingsViewModel
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope

internal fun testSettingsInputHandler(
    nav: NavigationController,
    ioScope: CoroutineScope,
    context: Context,
    settingsViewModel: SettingsViewModel,
    dialogInputHandler: DialogInputHandler,
) = SettingsInputHandler(
    nav = nav,
    ioScope = ioScope,
    settings = mockk(relaxed = true),
    platformConfig = mockk(relaxed = true),
    installedCoreService = mockk(relaxed = true),
    globalOverrides = mockk(relaxed = true),
    appsRepository = mockk(relaxed = true),
    setupCoordinator = mockk(relaxed = true),
    inputTesterController = mockk(relaxed = true),
    updateManager = mockk(relaxed = true),
    intentAuditor = mockk(relaxed = true),
    settingsViewModel = settingsViewModel,
    dialogInputHandler = dialogInputHandler,
    launcherActions = mockk(relaxed = true),
    activityActions = mockk(relaxed = true),
    emulatorMappingBuilder = mockk(relaxed = true),
    context = context,
    rommStore = mockk(relaxed = true),
    cannoliPaths = mockk(relaxed = true),
    raLoginController = mockk(relaxed = true),
    permissionsInputHandler = mockk(relaxed = true),
    coreDownloadService = mockk(relaxed = true),
    osdController = mockk(relaxed = true),
)
