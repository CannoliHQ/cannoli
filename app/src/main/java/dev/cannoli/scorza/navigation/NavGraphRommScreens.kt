package dev.cannoli.scorza.navigation

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import dev.cannoli.scorza.R
import dev.cannoli.scorza.ui.viewmodel.SettingsViewModel

@Composable
internal fun RommScreens(
    currentScreen: LauncherScreen,
    onListStateChanged: ((androidx.compose.foundation.lazy.LazyListState?) -> Unit)?,
    nav: NavigationController?,
    inputRouter: dev.cannoli.scorza.input.InputRouter?,
    rommBrowseViewModel: dev.cannoli.scorza.ui.viewmodel.RommBrowseViewModel?,
    rommImageLoader: coil.ImageLoader?,
    rommHost: String,
    rommArtType: dev.cannoli.scorza.romm.RommArtType,
    rommDownloader: dev.cannoli.scorza.download.Downloader?,
    appSettings: SettingsViewModel.AppSettings,
    listFontSize: TextUnit,
    listLineHeight: TextUnit,
    labels: dev.cannoli.ui.ButtonStyle,
    listVerticalPadding: Dp,
) {
    when (currentScreen) {
        is LauncherScreen.RommPlatformList -> {
            if (inputRouter != null) {
                val handler = remember { inputRouter.currentHandler() }
                dev.cannoli.scorza.input.screen.compose.ScreenInput(handler)
            }
            val platforms = rommBrowseViewModel?.platforms?.collectAsState()?.value ?: emptyList()
            val collections = rommBrowseViewModel?.collections?.collectAsState()?.value ?: emptyList()
            androidx.compose.runtime.LaunchedEffect(Unit) {
                rommBrowseViewModel?.enterBrowse()
            }
            val showCollectionsRow = collections.isNotEmpty()
            val syncStatus = rommBrowseViewModel?.syncStatus?.collectAsState()?.value
            val syncProgress = rommBrowseViewModel?.syncProgress?.collectAsState()?.value
            var emptyMessage: String? = null
            var syncFraction: Float? = null
            if (platforms.isEmpty()) {
                if (rommBrowseViewModel?.isServerUnsupported() == true) {
                    emptyMessage = androidx.compose.ui.res.stringResource(dev.cannoli.ui.R.string.romm_server_too_old)
                } else when (syncStatus) {
                    dev.cannoli.scorza.romm.cache.RommSyncCoordinator.SyncStatus.SYNCING ->
                        if (syncProgress != null && syncProgress.total > 0) {
                            val platformName = syncProgress.platform
                            emptyMessage = if (platformName != null)
                                androidx.compose.ui.res.stringResource(dev.cannoli.ui.R.string.romm_syncing_platform, platformName)
                            else androidx.compose.ui.res.stringResource(dev.cannoli.ui.R.string.romm_syncing)
                            syncFraction = syncProgress.completed.toFloat() / syncProgress.total
                        } else {
                            emptyMessage = androidx.compose.ui.res.stringResource(dev.cannoli.ui.R.string.romm_syncing)
                        }
                    dev.cannoli.scorza.romm.cache.RommSyncCoordinator.SyncStatus.ERROR ->
                        emptyMessage = androidx.compose.ui.res.stringResource(dev.cannoli.ui.R.string.romm_sync_error)
                    else -> {}
                }
            }
            val effectiveItemCount = platforms.size + (if (showCollectionsRow) 1 else 0)
            androidx.compose.runtime.LaunchedEffect(effectiveItemCount) {
                if (currentScreen.itemCount != effectiveItemCount) nav?.replaceTop(currentScreen.copy(itemCount = effectiveItemCount))
            }
            dev.cannoli.scorza.ui.screens.RommPlatformListScreen(
                platforms = platforms,
                selectedIndex = currentScreen.selectedIndex,
                scrollTarget = currentScreen.scrollTarget,
                showCollectionsRow = showCollectionsRow,
                collectionCount = collections.size,
                backgroundImagePath = appSettings.backgroundImagePath,
                backgroundTint = appSettings.backgroundTint,
                listFontSize = listFontSize,
                listLineHeight = listLineHeight,
                listVerticalPadding = listVerticalPadding,
                onListStateChanged = onListStateChanged,
                buttonStyle = labels,
                emptyMessage = emptyMessage,
                progress = syncFraction,
                syncing = syncStatus == dev.cannoli.scorza.romm.cache.RommSyncCoordinator.SyncStatus.SYNCING,
            )
        }
        is LauncherScreen.RommGameList -> {
            if (inputRouter != null) {
                val handler = remember { inputRouter.currentHandler() }
                dev.cannoli.scorza.input.screen.compose.ScreenInput(handler)
            }
            val loaded = rommBrowseViewModel?.games?.collectAsState()?.value
            // null (not loaded), a different platform's id, or a stale search term means the rows are not
            // ours yet, so we render a loading blank rather than flashing the previous list/art or "No results".
            val loading = loaded?.id != currentScreen.platform.id ||
                loaded?.search != currentScreen.search.ifBlank { null }
            val games = if (loading) emptyList() else loaded?.rows ?: emptyList()
            androidx.compose.runtime.LaunchedEffect(currentScreen.platform.id, currentScreen.search) {
                rommBrowseViewModel?.openPlatform(currentScreen.platform, currentScreen.search.ifBlank { null })
            }
            androidx.compose.runtime.LaunchedEffect(loading, games.size) {
                if (!loading && currentScreen.itemCount != games.size) nav?.replaceTop(currentScreen.copy(itemCount = games.size))
            }
            val loader = rommImageLoader
            val queueItems = rommDownloader?.state?.collectAsState()?.value ?: emptyList()
            val doneForPlatform = queueItems.count {
                it.tag == currentScreen.platform.cannoliTag &&
                    it.status == dev.cannoli.scorza.download.DownloadStatus.Done
            }
            androidx.compose.runtime.LaunchedEffect(doneForPlatform) {
                if (doneForPlatform > 0) rommBrowseViewModel?.refreshLocalState()
            }
            val multiSelect = rommBrowseViewModel?.multiSelect?.collectAsState()?.value ?: false
            val checkedIds = rommBrowseViewModel?.checkedIds?.collectAsState()?.value ?: emptySet()
            if (loader != null) {
                dev.cannoli.scorza.ui.screens.RommGameListScreen(
                    title = currentScreen.platform.displayName,
                    search = currentScreen.search,
                    games = games,
                    loading = loading,
                    selectedIndex = currentScreen.selectedIndex,
                    scrollTarget = currentScreen.scrollTarget,
                    host = rommHost,
                    artWidth = appSettings.artWidth,
                    artType = rommArtType,
                    multiSelect = multiSelect,
                    checkedIds = checkedIds,
                    showFirmware = currentScreen.platform.firmwareCount > 0,
                    imageLoader = loader,
                    backgroundImagePath = appSettings.backgroundImagePath,
                    backgroundTint = appSettings.backgroundTint,
                    listFontSize = listFontSize,
                    listLineHeight = listLineHeight,
                    listVerticalPadding = listVerticalPadding,
                    onListStateChanged = onListStateChanged,
                    buttonStyle = labels,
                )
            }
        }
        is LauncherScreen.RommCollectionGroups -> {
            if (inputRouter != null) {
                val handler = remember { inputRouter.currentHandler() }
                dev.cannoli.scorza.input.screen.compose.ScreenInput(handler)
            }
            androidx.compose.runtime.LaunchedEffect(Unit) { rommBrowseViewModel?.loadCollectionCounts() }
            val counts = rommBrowseViewModel?.groupCounts?.collectAsState()?.value ?: emptyMap()
            val enabled = rommBrowseViewModel?.enabledGroups() ?: emptySet()
            val rows = listOf(
                dev.cannoli.scorza.romm.RommCollectionGroup.USER to stringResource(dev.cannoli.ui.R.string.romm_collections_my),
                dev.cannoli.scorza.romm.RommCollectionGroup.VIRTUAL to stringResource(dev.cannoli.ui.R.string.romm_collections_virtual),
                dev.cannoli.scorza.romm.RommCollectionGroup.SMART to stringResource(dev.cannoli.ui.R.string.romm_collections_smart),
            ).filter { it.first in enabled }
             .map { dev.cannoli.scorza.ui.screens.RommGroupRow(it.first, it.second, counts[it.first] ?: 0) }
            androidx.compose.runtime.LaunchedEffect(rows.size) {
                if (currentScreen.itemCount != rows.size) nav?.replaceTop(currentScreen.copy(itemCount = rows.size))
            }
            dev.cannoli.scorza.ui.screens.RommCollectionGroupsScreen(
                rows = rows,
                selectedIndex = currentScreen.selectedIndex,
                scrollTarget = currentScreen.scrollTarget,
                backgroundImagePath = appSettings.backgroundImagePath,
                backgroundTint = appSettings.backgroundTint,
                listFontSize = listFontSize,
                listLineHeight = listLineHeight,
                listVerticalPadding = listVerticalPadding,
                onListStateChanged = onListStateChanged,
                buttonStyle = labels,
            )
        }
        is LauncherScreen.RommVirtualTypes -> {
            if (inputRouter != null) {
                val handler = remember { inputRouter.currentHandler() }
                dev.cannoli.scorza.input.screen.compose.ScreenInput(handler)
            }
            androidx.compose.runtime.LaunchedEffect(Unit) { rommBrowseViewModel?.loadCollectionCounts() }
            val typeCounts = rommBrowseViewModel?.virtualTypeCounts?.collectAsState()?.value ?: emptyList()
            val rows = typeCounts.map { (type, count) ->
                val label = dev.cannoli.scorza.romm.RommVirtualType.from(type)?.let { stringResource(it.labelRes) } ?: type
                dev.cannoli.scorza.ui.screens.RommTypeRow(type, label, count)
            }
            androidx.compose.runtime.LaunchedEffect(rows.size) {
                if (currentScreen.itemCount != rows.size) nav?.replaceTop(currentScreen.copy(itemCount = rows.size))
            }
            dev.cannoli.scorza.ui.screens.RommVirtualTypesScreen(
                rows = rows,
                selectedIndex = currentScreen.selectedIndex,
                scrollTarget = currentScreen.scrollTarget,
                backgroundImagePath = appSettings.backgroundImagePath,
                backgroundTint = appSettings.backgroundTint,
                listFontSize = listFontSize,
                listLineHeight = listLineHeight,
                listVerticalPadding = listVerticalPadding,
                onListStateChanged = onListStateChanged,
                buttonStyle = labels,
            )
        }
        is LauncherScreen.RommCollectionList -> {
            if (inputRouter != null) {
                val handler = remember { inputRouter.currentHandler() }
                dev.cannoli.scorza.input.screen.compose.ScreenInput(handler)
            }
            val key = currentScreen.group.name + (currentScreen.virtualType?.let { ":$it" } ?: "")
            val loaded = rommBrowseViewModel?.collectionList?.collectAsState()?.value
            val items = if (loaded?.id == key) loaded.rows else emptyList()
            androidx.compose.runtime.LaunchedEffect(key) {
                rommBrowseViewModel?.openCollections(currentScreen.group, currentScreen.virtualType)
            }
            androidx.compose.runtime.LaunchedEffect(items.size) {
                if (currentScreen.itemCount != items.size) nav?.replaceTop(currentScreen.copy(itemCount = items.size))
            }
            val title = currentScreen.virtualType
                ?.let { vtype ->
                    val typeLabel = dev.cannoli.scorza.romm.RommVirtualType.from(vtype)?.let { t -> stringResource(t.labelRes) } ?: vtype
                    "${stringResource(dev.cannoli.ui.R.string.romm_collection_group_virtual)}: $typeLabel"
                }
                ?: stringResource(when (currentScreen.group) {
                    dev.cannoli.scorza.romm.RommCollectionGroup.USER -> dev.cannoli.ui.R.string.romm_collections_my
                    dev.cannoli.scorza.romm.RommCollectionGroup.SMART -> dev.cannoli.ui.R.string.romm_collections_smart
                    dev.cannoli.scorza.romm.RommCollectionGroup.VIRTUAL -> dev.cannoli.ui.R.string.romm_collections_virtual
                })
            dev.cannoli.scorza.ui.screens.RommCollectionListScreen(
                title = title,
                collections = items,
                selectedIndex = currentScreen.selectedIndex,
                scrollTarget = currentScreen.scrollTarget,
                backgroundImagePath = appSettings.backgroundImagePath,
                backgroundTint = appSettings.backgroundTint,
                listFontSize = listFontSize,
                listLineHeight = listLineHeight,
                listVerticalPadding = listVerticalPadding,
                onListStateChanged = onListStateChanged,
                buttonStyle = labels,
            )
        }
        is LauncherScreen.RommCollectionGameList -> {
            if (inputRouter != null) {
                val handler = remember { inputRouter.currentHandler() }
                dev.cannoli.scorza.input.screen.compose.ScreenInput(handler)
            }
            val loaded = rommBrowseViewModel?.collectionGames?.collectAsState()?.value
            val loading = loaded?.id != currentScreen.collection.id ||
                loaded?.search != currentScreen.search.ifBlank { null }
            val games = if (loading) emptyList() else loaded?.rows ?: emptyList()
            androidx.compose.runtime.LaunchedEffect(currentScreen.collection.id, currentScreen.search) {
                rommBrowseViewModel?.openCollection(currentScreen.collection, currentScreen.search.ifBlank { null })
            }
            androidx.compose.runtime.LaunchedEffect(loading, games.size) {
                if (!loading && currentScreen.itemCount != games.size) nav?.replaceTop(currentScreen.copy(itemCount = games.size))
            }
            val queueItems = rommDownloader?.state?.collectAsState()?.value ?: emptyList()
            val doneCount = queueItems.count {
                it.status == dev.cannoli.scorza.download.DownloadStatus.Done
            }
            androidx.compose.runtime.LaunchedEffect(doneCount) {
                if (doneCount > 0) rommBrowseViewModel?.refreshCollectionLocalState()
            }
            val multiSelect = rommBrowseViewModel?.multiSelect?.collectAsState()?.value ?: false
            val checkedIds = rommBrowseViewModel?.checkedIds?.collectAsState()?.value ?: emptySet()
            val loader = rommImageLoader
            if (loader != null) {
                dev.cannoli.scorza.ui.screens.RommCollectionGameListScreen(
                    title = currentScreen.collection.name,
                    search = currentScreen.search,
                    games = games,
                    loading = loading,
                    selectedIndex = currentScreen.selectedIndex,
                    scrollTarget = currentScreen.scrollTarget,
                    host = rommHost,
                    artWidth = appSettings.artWidth,
                    artType = rommArtType,
                    multiSelect = multiSelect,
                    checkedIds = checkedIds,
                    imageLoader = loader,
                    backgroundImagePath = appSettings.backgroundImagePath,
                    backgroundTint = appSettings.backgroundTint,
                    listFontSize = listFontSize,
                    listLineHeight = listLineHeight,
                    listVerticalPadding = listVerticalPadding,
                    onListStateChanged = onListStateChanged,
                    buttonStyle = labels,
                )
            }
        }
        is LauncherScreen.RommGlobalSearch -> {
            if (inputRouter != null) {
                val handler = remember { inputRouter.currentHandler() }
                dev.cannoli.scorza.input.screen.compose.ScreenInput(handler)
            }
            val loaded = rommBrowseViewModel?.searchResults?.collectAsState()?.value
            val loading = loaded?.id != currentScreen.term
            val results = if (loading) emptyList() else loaded?.rows ?: emptyList()
            val allPlatforms = rommBrowseViewModel?.allPlatforms?.collectAsState()?.value ?: emptyList()
            val platformTagById = remember(allPlatforms) { allPlatforms.associate { it.id to it.cannoliTag.uppercase() } }
            androidx.compose.runtime.LaunchedEffect(currentScreen.term) {
                rommBrowseViewModel?.loadGlobalSearch(dev.cannoli.scorza.romm.RommSearchQuery(currentScreen.term))
            }
            androidx.compose.runtime.LaunchedEffect(loading, results.size) {
                if (!loading && currentScreen.itemCount != results.size) nav?.replaceTop(currentScreen.copy(itemCount = results.size))
            }
            val loader = rommImageLoader
            if (loader != null) {
                dev.cannoli.scorza.ui.screens.RommGameListScreen(
                    title = stringResource(dev.cannoli.ui.R.string.romm_global_search_title),
                    search = currentScreen.term,
                    games = results,
                    loading = loading,
                    selectedIndex = currentScreen.selectedIndex,
                    scrollTarget = currentScreen.scrollTarget,
                    host = rommHost,
                    artWidth = appSettings.artWidth,
                    artType = rommArtType,
                    imageLoader = loader,
                    backgroundImagePath = appSettings.backgroundImagePath,
                    backgroundTint = appSettings.backgroundTint,
                    listFontSize = listFontSize,
                    listLineHeight = listLineHeight,
                    listVerticalPadding = listVerticalPadding,
                    onListStateChanged = onListStateChanged,
                    buttonStyle = labels,
                    platformLabelForGame = { g -> platformTagById[g.platformId] },
                )
            }
        }
        is LauncherScreen.RommFirmwareList -> {
            if (inputRouter != null) {
                val handler = remember { inputRouter.currentHandler() }
                dev.cannoli.scorza.input.screen.compose.ScreenInput(handler)
            }
            androidx.compose.runtime.LaunchedEffect(currentScreen.platform.id) {
                if (currentScreen.loading) {
                    val result = runCatching {
                        rommBrowseViewModel?.loadFirmware(currentScreen.platform.id, currentScreen.platform.cannoliTag) ?: emptyList()
                    }
                    nav?.replaceTop(currentScreen.copy(
                        rows = result.getOrDefault(emptyList()),
                        loading = false,
                        error = result.isFailure,
                    ))
                }
            }
            dev.cannoli.scorza.ui.screens.RommFirmwareListScreen(
                title = stringResource(dev.cannoli.ui.R.string.romm_firmware_screen_title, currentScreen.platform.displayName),
                rows = currentScreen.rows,
                checkedIds = currentScreen.checkedIds,
                loading = currentScreen.loading,
                error = currentScreen.error,
                selectedIndex = currentScreen.selectedIndex,
                scrollTarget = currentScreen.scrollTarget,
                backgroundImagePath = appSettings.backgroundImagePath,
                backgroundTint = appSettings.backgroundTint,
                listFontSize = listFontSize,
                listLineHeight = listLineHeight,
                listVerticalPadding = listVerticalPadding,
                buttonStyle = labels,
            )
        }
        is LauncherScreen.RommGameDetail -> {
            val loader = rommImageLoader
            val downloads = rommDownloader?.state?.collectAsState()?.value ?: emptyList()
            val downloaded = downloads.any {
                (it.payload as? dev.cannoli.scorza.romm.download.RommPayload)?.rommId == currentScreen.game.id && it.status == dev.cannoli.scorza.download.DownloadStatus.Done
            }
            androidx.compose.runtime.LaunchedEffect(downloaded) {
                if (downloaded && currentScreen.localState != dev.cannoli.scorza.romm.LocalState.PRESENT) {
                    nav?.replaceTop(currentScreen.copy(localState = dev.cannoli.scorza.romm.LocalState.PRESENT))
                }
            }
            if (loader != null) {
                dev.cannoli.scorza.ui.screens.RommGameDetailScreen(
                    game = currentScreen.game,
                    platformName = currentScreen.platformName,
                    localState = currentScreen.localState,
                    host = rommHost,
                    artType = rommArtType,
                    imageLoader = loader,
                    scrollStep = currentScreen.scrollStep,
                    onScrollStepChanged = { nav?.replaceTop(currentScreen.copy(scrollStep = it)) },
                    memberCount = currentScreen.versionCount,
                    listFontSize = listFontSize,
                    listLineHeight = listLineHeight,
                    buttonStyle = labels,
                )
            }
        }
        else -> {}
    }
}
