package dev.cannoli.scorza.input

import android.content.Context
import dev.cannoli.scorza.navigation.NavigationController
import dev.cannoli.scorza.romm.sync.SaveSyncStatusHolder
import dev.cannoli.scorza.ui.viewmodel.SettingsViewModel
import dev.cannoli.scorza.ui.viewmodel.SystemListViewModel
import dev.cannoli.scorza.updater.UpdateManager
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope

internal fun testDialogInputHandler(
    nav: NavigationController,
    ioScope: CoroutineScope,
    context: Context,
    settingsViewModel: SettingsViewModel = mockk(relaxed = true),
    systemListViewModel: SystemListViewModel = mockk(relaxed = true),
    updateManager: UpdateManager = mockk(relaxed = true),
    saveSyncStatusHolder: SaveSyncStatusHolder = SaveSyncStatusHolder(),
) = DialogInputHandler(
    nav = nav,
    ioScope = ioScope,
    context = context,
    settings = mockk(relaxed = true),
    collectionManager = mockk(relaxed = true),
    recentlyPlayedManager = mockk(relaxed = true),
    platformResolver = mockk(relaxed = true),
    installedCoreService = mockk(relaxed = true),
    launchManager = mockk(relaxed = true),
    updateManager = updateManager,
    atomicRename = mockk(relaxed = true),
    scanner = mockk(relaxed = true),
    romDirectoryWalker = mockk(relaxed = true),
    settingsViewModel = settingsViewModel,
    gameListViewModel = mockk(relaxed = true),
    systemListViewModel = systemListViewModel,
    romsRepository = mockk(relaxed = true),
    gameOverrideStore = mockk(relaxed = true),
    appsRepository = mockk(relaxed = true),
    artworkLookup = mockk(relaxed = true),
    launcherActions = mockk(relaxed = true),
    activityActions = mockk(relaxed = true),
    controllersViewModel = mockk(relaxed = true),
    emulatorMappingBuilder = mockk(relaxed = true),
    rommStore = mockk(relaxed = true),
    rommDownloader = mockk(relaxed = true),
    rommBrowseViewModel = mockk(relaxed = true),
    rommArtFetcher = mockk(relaxed = true),
    raPreloadController = mockk(relaxed = true),
    deviceRegistrar = mockk(relaxed = true),
    saveSyncService = mockk(relaxed = true),
    slotManager = mockk(relaxed = true),
    saveSlotsHandler = mockk(relaxed = true),
    syncHistoryStore = mockk(relaxed = true),
    pendingConflictStore = mockk(relaxed = true),
    saveSyncStatusHolder = saveSyncStatusHolder,
    osdController = mockk(relaxed = true),
    rommDevicePairing = mockk(relaxed = true),
)
