package dev.cannoli.scorza.input

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.scopes.ActivityScoped
import dev.cannoli.scorza.config.PlatformConfig
import dev.cannoli.scorza.db.AppsRepository
import dev.cannoli.scorza.db.CollectionsRepository
import dev.cannoli.scorza.db.LibraryRef
import dev.cannoli.scorza.db.RecentlyPlayedRepository
import dev.cannoli.scorza.db.RomsRepository
import dev.cannoli.scorza.di.IoScope
import dev.cannoli.scorza.launcher.LaunchManager
import dev.cannoli.scorza.model.AppType
import dev.cannoli.scorza.model.CollectionType
import dev.cannoli.scorza.model.ListItem
import dev.cannoli.scorza.navigation.NavigationController
import dev.cannoli.scorza.server.KitchenManager
import dev.cannoli.scorza.settings.ContentMode
import dev.cannoli.scorza.settings.SettingsRepository
import dev.cannoli.scorza.ui.screens.DialogState
import dev.cannoli.scorza.ui.viewmodel.GameListViewModel
import dev.cannoli.scorza.ui.viewmodel.SettingsViewModel
import dev.cannoli.scorza.ui.viewmodel.SystemListViewModel
import dev.cannoli.scorza.util.ArcadeTitleLookup
import dev.cannoli.scorza.util.ArtworkLookup
import dev.cannoli.ui.components.COLOR_GRID_COLS
import dev.cannoli.ui.theme.COLOR_PRESETS
import dev.cannoli.ui.theme.colorToArgbLong
import dev.cannoli.ui.theme.hexToColor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

@ActivityScoped
class LauncherActions @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoScope private val ioScope: CoroutineScope,
    private val settings: SettingsRepository,
    private val collectionsRepository: CollectionsRepository,
    private val recentlyPlayedRepository: RecentlyPlayedRepository,
    private val romsRepository: RomsRepository,
    private val appsRepository: AppsRepository,
    private val launchManager: LaunchManager,
    private val platformConfig: PlatformConfig,
    private val artworkLookup: ArtworkLookup,
    private val arcadeTitleLookup: ArcadeTitleLookup,
    private val nav: NavigationController,
    private val systemListViewModel: SystemListViewModel,
    private val gameListViewModel: GameListViewModel,
    private val settingsViewModel: SettingsViewModel,
) {

    fun rescanSystemList() {
        val fghStem = validateFghStem()
        gameListViewModel.showFavoriteStars = settings.contentMode != ContentMode.FIVE_GAME_HANDHELD
        systemListViewModel.scan(
            showRecentlyPlayed = settings.showRecentlyPlayed,
            showEmpty = settings.showEmpty,
            contentMode = settings.contentMode,
            fghCollectionStem = fghStem,
            toolsName = settings.toolsName,
            portsName = settings.portsName,
            onReady = {
                if (fghStem != null) {
                    scanResumableGames()
                }
            }
        )
    }

    fun validateFghStem(): String? {
        if (settings.contentMode != ContentMode.FIVE_GAME_HANDHELD) return null
        val all = collectionsRepository.all().filter { it.type == CollectionType.STANDARD }
        val stem = settings.fghCollectionStem
        if (stem != null && all.any { it.displayName == stem }) return stem
        val fallback = all.firstOrNull()?.displayName
        settings.fghCollectionStem = fallback
        return fallback
    }

    fun scanResumableGames() {
        val gameListRoms = gameListViewModel.state.value.items
            .filterIsInstance<ListItem.RomItem>()
            .map { it.rom }
        val systemListRoms = systemListViewModel.state.value.items
            .filterIsInstance<SystemListViewModel.ListItem.GameItem>()
            .mapNotNull { (it.item as? ListItem.RomItem)?.rom }
        val roms = (gameListRoms + systemListRoms).distinctBy { it.path.absolutePath }
        ioScope.launch {
            val result = launchManager.findResumableRoms(roms)
            withContext(Dispatchers.Main) { nav.resumableGames = result }
        }
    }

    fun invalidateAllLibraryCaches() {
        artworkLookup.invalidateAll()
        arcadeTitleLookup.invalidateAll()
    }

    fun launchSelected(item: ListItem, resume: Boolean): DialogState? = when (item) {
        is ListItem.RomItem ->
            if (resume) launchManager.resumeRom(item.rom) else launchManager.launchRom(item.rom)
        is ListItem.AppItem -> launchManager.launchApp(item.app)
        else -> null
    }

    fun recordRecentlyPlayedByPath(path: String) {
        ioScope.launch {
            resolvePathToRef(path)?.let { recentlyPlayedRepository.record(it) }
        }
    }

    fun openColorPicker(settingKey: String) {
        val hex = settingsViewModel.getColorHex(settingKey)
        val color = hexToColor(hex) ?: androidx.compose.ui.graphics.Color.White
        val argb = colorToArgbLong(color)
        val idx = COLOR_PRESETS.indexOfFirst { it.color == argb }
        val row = if (idx >= 0) idx / COLOR_GRID_COLS else 0
        val col = if (idx >= 0) idx % COLOR_GRID_COLS else 0
        nav.dialogState.value = DialogState.ColorPicker(
            settingKey = settingKey,
            title = colorSettingTitle(settingKey),
            currentColor = argb,
            selectedRow = row,
            selectedCol = col
        )
    }

    fun openKitchen() {
        val km = KitchenManager
        val sdRoot = File(settings.sdCardRoot)
        if (!km.isRunning) km.toggle(sdRoot, context.assets, settings.kitchenCodeBypass)
        else km.setCodeBypass(settings.kitchenCodeBypass)
        nav.dialogState.value = DialogState.Kitchen(
            urls = km.getUrls(hasActiveVpn()),
            pin = km.pin,
            requirePin = !settings.kitchenCodeBypass
        )
    }

    fun handleSystemListRename(state: DialogState.RenameInput) {
        val newName = state.currentName.trim()
        if (newName.isEmpty() || newName == state.gameName) {
            nav.dialogState.value = DialogState.None
            return
        }
        val item = systemListViewModel.getSelectedItem()
        when (item) {
            is SystemListViewModel.ListItem.PlatformItem -> {
                ioScope.launch {
                    platformConfig.setDisplayName(item.platform.tag, newName)
                    rescanSystemList()
                }
            }
            is SystemListViewModel.ListItem.ToolsFolder -> {
                settings.toolsName = newName
                rescanSystemList()
            }
            is SystemListViewModel.ListItem.PortsFolder -> {
                settings.portsName = newName
                rescanSystemList()
            }
            is SystemListViewModel.ListItem.CollectionItem -> {
                ioScope.launch {
                    val id = collectionsRepository.all().firstOrNull { it.displayName == item.name }?.id
                    if (id != null) collectionsRepository.rename(id, newName)
                    rescanSystemList()
                }
            }
            else -> {}
        }
        nav.dialogState.value = DialogState.None
    }

    private fun resolvePathToRef(path: String): LibraryRef? {
        return if (path.startsWith("/apps/")) {
            val parts = path.removePrefix("/apps/").split("/", limit = 2)
            if (parts.size == 2) {
                val type = runCatching { AppType.valueOf(parts[0]) }.getOrNull()
                type?.let { appsRepository.byPackage(it, parts[1]) }?.let { LibraryRef.App(it.id) }
            } else null
        } else {
            romsRepository.gameByPath(path)?.let { LibraryRef.Rom(it.id) }
        }
    }

    private fun hasActiveVpn(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    }

    private fun colorSettingTitle(settingKey: String): String {
        val labelRes = when (settingKey) {
            "color_accent" -> dev.cannoli.scorza.R.string.setting_color_accent
            "color_highlight" -> dev.cannoli.scorza.R.string.setting_color_highlight
            "color_highlight_text" -> dev.cannoli.scorza.R.string.setting_color_highlight_text
            "color_text" -> dev.cannoli.scorza.R.string.setting_color_text
            "color_title" -> dev.cannoli.scorza.R.string.setting_color_title
            else -> return ""
        }
        return context.getString(labelRes)
    }
}
