package dev.cannoli.scorza.input

import android.content.Context
import dev.cannoli.scorza.db.RomsRepository
import dev.cannoli.scorza.navigation.NavigationController
import dev.cannoli.scorza.download.Downloader
import dev.cannoli.scorza.romm.sync.SaveSyncStatusHolder
import dev.cannoli.scorza.ui.viewmodel.GameListViewModel
import dev.cannoli.scorza.ui.viewmodel.SettingsViewModel
import dev.cannoli.scorza.ui.viewmodel.SystemListViewModel
import dev.cannoli.scorza.updater.UpdateManager
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow

internal fun testDialogInputHandler(
    nav: NavigationController,
    ioScope: CoroutineScope,
    context: Context,
    settingsViewModel: SettingsViewModel = mockk(relaxed = true),
    systemListViewModel: SystemListViewModel = mockk(relaxed = true),
    updateManager: UpdateManager = mockk(relaxed = true),
    saveSyncStatusHolder: SaveSyncStatusHolder = SaveSyncStatusHolder(),
    rommDownloader: Downloader = emptyQueueDownloader(),
    gameListViewModel: GameListViewModel = mockk(relaxed = true),
    romsRepository: RomsRepository = mockk(relaxed = true),
    // A relaxed SettingsRepository answers every String with "", so the default here is logged
    // out of RetroAchievements. Rows that only exist for a connected account are absent unless a
    // test says otherwise.
    raToken: String = "",
) = DialogInputHandler(
    nav = nav,
    ioScope = ioScope,
    context = context,
    settings = mockk(relaxed = true) { every { this@mockk.raToken } returns raToken },
    collectionManager = mockk(relaxed = true),
    recentlyPlayedManager = mockk(relaxed = true),
    platformResolver = mockk(relaxed = true),
    installedCoreService = mockk(relaxed = true),
    updateManager = updateManager,
    atomicRename = mockk(relaxed = true),
    scanner = mockk(relaxed = true),
    romDirectoryWalker = mockk(relaxed = true),
    settingsViewModel = settingsViewModel,
    gameListViewModel = gameListViewModel,
    systemListViewModel = systemListViewModel,
    romsRepository = romsRepository,
    gameOverrideStore = mockk(relaxed = true),
    appsRepository = mockk(relaxed = true),
    artworkLookup = mockk(relaxed = true),
    launcherActions = mockk(relaxed = true),
    activityActions = mockk(relaxed = true),
    controllersViewModel = mockk(relaxed = true),
    emulatorMappingBuilder = mockk(relaxed = true),
    rommStore = mockk(relaxed = true),
    rommDownloader = rommDownloader,
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
    coreUpdateController = mockk(relaxed = true),
    shaderUpdateController = mockk(relaxed = true),
)

// A relaxed mock answers queue.state.value with a bare Object, which blows up on the List cast the
// moment anything builds the quick menu. Stub it so the rebuild path is reachable from tests.
private fun emptyQueueDownloader(): Downloader = mockk(relaxed = true) {
    every { state } returns MutableStateFlow(emptyList())
}
