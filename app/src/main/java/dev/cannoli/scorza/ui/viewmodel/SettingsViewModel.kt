package dev.cannoli.scorza.ui.viewmodel

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.annotation.StringRes
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.scopes.ActivityScoped
import dev.cannoli.scorza.R
import dev.cannoli.scorza.db.CollectionsRepository
import dev.cannoli.scorza.model.CollectionType
import dev.cannoli.scorza.settings.ArtScale
import dev.cannoli.scorza.settings.BatteryDisplay
import dev.cannoli.scorza.settings.ContentMode
import dev.cannoli.scorza.settings.IgmSettingsMode
import dev.cannoli.scorza.settings.SettingsRepository
import dev.cannoli.scorza.settings.TextSize
import dev.cannoli.scorza.settings.TimeFormat
import dev.cannoli.scorza.util.FontNameParser
import dev.cannoli.scorza.util.sortedNatural
import dev.cannoli.scorza.di.AppFonts
import dev.cannoli.ui.BULLET
import dev.cannoli.ui.theme.hexToColor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import dev.cannoli.scorza.updater.ReleaseChannel

@ActivityScoped
class SettingsViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val appFonts: AppFonts,
    @ApplicationContext private val context: Context,
    private val rommStore: dev.cannoli.scorza.romm.RommConnectionStore,
    private val pathsProvider: dev.cannoli.scorza.di.CannoliPathsProvider,
) {
    private var packageManager: PackageManager? = null
    private var appPackageName: String? = null
    private var collectionsRepository: CollectionsRepository? = null

    val isTelevision: Boolean
        get() = dev.cannoli.scorza.util.DeviceType.isTv(context)

    private fun isDefaultLauncher(): Boolean {
        val pm = packageManager ?: return false
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolved = pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        return resolved?.activityInfo?.packageName == appPackageName
    }

    data class FontOption(val key: String, val label: String, val fontFamily: FontFamily, val typeface: android.graphics.Typeface?)

    private var cachedFontOptions: List<FontOption>? = null
    private var cachedFontsRoot: String? = null

    // Built on first read, not at construction: the view model is injected during onCreate, before
    // first run has chosen where Cannoli lives. Keyed on the root so the custom fonts appear once
    // storage resolves, and rebuilt when a caller asks for a rescan.
    private val fontOptions: List<FontOption>
        get() {
            val root = settings.sdCardRootOrNull
            cachedFontOptions?.let { if (cachedFontsRoot == root) return it }
            return buildFontOptions().also {
                cachedFontOptions = it
                cachedFontsRoot = root
            }
        }

    private fun invalidateFontOptions() {
        cachedFontOptions = null
    }

    private fun buildFontOptions(): List<FontOption> = buildList {
        add(FontOption("default", "Default", appFonts.mplus1Code, appFonts.mplus1CodeTypeface))
        add(FontOption("the_og", "The OG", appFonts.bpReplay, appFonts.bpReplayTypeface))
        // Custom fonts live under the chosen root, so before the storage step there are none to find.
        val root = settings.sdCardRootOrNull ?: return@buildList
        val fontsDir = java.io.File(root, "Config/Fonts")
        val exts = setOf("ttf", "otf")
        val customFiles = fontsDir.listFiles()
            ?.filter { it.isFile && it.extension.lowercase(java.util.Locale.ROOT) in exts }
            ?: emptyList()
        for (file in customFiles.sortedNatural { it.name }) {
            val typeface = try { android.graphics.Typeface.createFromFile(file) } catch (_: Exception) { null } ?: continue
            val family = FontFamily(androidx.compose.ui.text.font.Typeface(typeface))
            val label = FontNameParser.getFamilyName(file) ?: file.nameWithoutExtension
            add(FontOption(file.name, label, family, typeface))
        }
    }

    private fun resolveFont(): FontFamily {
        val chosen = fontOptions.firstOrNull { it.key == settings.font } ?: return appFonts.mplus1Code
        val sample = dev.cannoli.scorza.i18n.LanguageCatalog.byTag(settings.language)?.coverageSample
        if (sample != null) {
            val typeface = chosen.typeface
            if (typeface == null || !dev.cannoli.scorza.i18n.FontCoverage.covers(typeface, sample)) {
                return appFonts.mplus1Code
            }
        }
        return chosen.fontFamily
    }

    data class SettingsItem(
        val key: String,
        @param:StringRes val labelRes: Int,
        val labelText: String? = null,
        @param:StringRes val valueRes: Int? = null,
        val valueText: String? = null,
        val isEditable: Boolean = false,
        // A row that does something when confirmed, rather than cycling a value or opening a
        // screen. It can still show a value, which is what distinguishes it: a status the action
        // acts on, like when cores were last updated.
        val isAction: Boolean = false,
        val canCycle: Boolean = true,
        val swatchColor: Color? = null,
        val disabled: Boolean = false
    )

    data class Category(
        val key: SettingsCategory,
        @param:StringRes val labelRes: Int
    )

    var raPassword: String = ""

    var updateInfo: dev.cannoli.scorza.updater.UpdateInfo? = null
        set(value) {
            field = value
            reloadCategories()
        }

    data class State(
        val categories: List<Category> = emptyList(),
        val categoryIndex: Int = 0,
        val activeCategory: SettingsCategory? = null,
        val parentCategory: SettingsCategory? = null,
        val parentSelectedIndex: Int = 0,
        @param:StringRes val activeCategoryLabel: Int? = null,
        val items: List<SettingsItem> = emptyList(),
        val selectedIndex: Int = 0
    ) {
        val inSubList: Boolean get() = activeCategory != null
    }

    data class AppSettings(
        val use24h: Boolean = false,
        val backgroundImagePath: String? = null,
        val backgroundTint: Int = 0,
        val textSize: TextSize = TextSize.DEFAULT,

        val fontFamily: FontFamily = FontFamily.Default,
        val languageTag: String = "",
        val title: String = "",
        val colorHighlight: Color = Color.White,
        val colorText: Color = Color.White,
        val colorHighlightText: Color = Color.Black,
        val colorAccent: Color = Color.White,
        val colorTitle: Color = Color.White,
        val colorBackground: Color = Color.Black,
        val colorStatusBar: Color = Color.White,
        val showWifi: Boolean = true,
        val showBluetooth: Boolean = true,
        val showVpn: Boolean = false,
        val showClock: Boolean = true,
        val batteryDisplay: BatteryDisplay = BatteryDisplay.DEFAULT,
        val showUpdate: Boolean = true,
        val showKitchen: Boolean = true,
        val showDownloads: Boolean = true,
        val swapPlayResume: Boolean = false,
        val mainMenuQuit: Boolean = false,
        val artWidth: Int = 40,
        val artScale: ArtScale = ArtScale.DEFAULT,
        val contentMode: ContentMode = ContentMode.PLATFORMS,
        val fghCollectionId: Long? = null,
        val fghCollectionDisplayName: String? = null,
        val portraitMarginPx: Int = 0,
        val screenGeometryWidth: Int = 100,
        val screenGeometryHeight: Int = 100,
        val screenGeometryX: Int = 0,
        val screenGeometryY: Int = 0,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private val _appSettings = MutableStateFlow(readAppSettings())
    val appSettings: StateFlow<AppSettings> = _appSettings

    private fun readAppSettings() = AppSettings(
        use24h = settings.timeFormat == TimeFormat.TWENTY_FOUR_HOUR,
        backgroundImagePath = settings.backgroundImagePath,
        backgroundTint = settings.backgroundTint,
        textSize = settings.textSize,
        fontFamily = resolveFont(),
        languageTag = settings.language,
        title = settings.title,
        colorHighlight = hexToColor(settings.colorHighlight) ?: Color.White,
        colorText = hexToColor(settings.colorText) ?: Color.White,
        colorHighlightText = hexToColor(settings.colorHighlightText) ?: Color.Black,
        colorAccent = hexToColor(settings.colorAccent) ?: Color.White,
        colorTitle = hexToColor(settings.colorTitle) ?: Color.White,
        colorBackground = hexToColor(settings.colorBackground) ?: Color.Black,
        colorStatusBar = hexToColor(settings.colorStatusBar) ?: Color.White,
        showWifi = settings.showWifi,
        showBluetooth = settings.showBluetooth,
        showVpn = settings.showVpn,
        showClock = settings.showClock,
        batteryDisplay = settings.batteryDisplay,
        showUpdate = settings.showUpdate,
        showKitchen = settings.showKitchen,
        showDownloads = settings.showDownloads,
        swapPlayResume = settings.swapPlayResume,
        mainMenuQuit = settings.mainMenuQuit,
        artWidth = settings.artWidth,
        artScale = settings.artScale,
        contentMode = settings.contentMode,
        fghCollectionId = settings.fghCollectionId,
        fghCollectionDisplayName = settings.fghCollectionId?.let { id ->
            collectionsRepository?.byId(id)?.displayName
        },
        portraitMarginPx = settings.portraitMarginPx,
        screenGeometryWidth = settings.screenGeometryWidth,
        screenGeometryHeight = settings.screenGeometryHeight,
        screenGeometryX = settings.screenGeometryX,
        screenGeometryY = settings.screenGeometryY,
    )

    private val allCategories = listOf(
        Category(SettingsCategory.GENERAL, R.string.settings_general),
        Category(SettingsCategory.DISPLAY, R.string.settings_display),
        Category(SettingsCategory.LIBRARY, R.string.settings_library),
        Category(SettingsCategory.INPUT, R.string.settings_input),
        Category(SettingsCategory.EMULATION, R.string.settings_emulation),
        Category(SettingsCategory.INTEGRATIONS, R.string.settings_integrations),
        Category(SettingsCategory.ADVANCED, R.string.settings_advanced),
    )

    private data class SettingsSnapshot(
        val textSize: TextSize,
        val font: String,
        val title: String,
        val timeFormat: TimeFormat,
        val bgImage: String?,
        val bgTint: Int,
        val colorHighlight: String,
        val colorText: String,
        val colorHighlightText: String,
        val colorAccent: String,
        val colorTitle: String,
        val colorBackground: String,
        val colorStatusBar: String,
        val swapPlayResume: Boolean,
        val showWifi: Boolean,
        val showBluetooth: Boolean,
        val showVpn: Boolean,
        val showClock: Boolean,
        val batteryDisplay: BatteryDisplay,
        val showRecentlyPlayed: Boolean,
        val showFavorites: Boolean,
        val contentMode: ContentMode,
        val igmSettingsMode: IgmSettingsMode,
        val fghCollectionId: Long?,
        val sdRoot: String,
        val romDirectory: String,
        val toolsName: String,
        val portsName: String,
        val releaseChannel: ReleaseChannel,
        val artWidth: Int,
        val artScale: ArtScale,
        val portraitMarginPx: Int,
        val screenGeometryWidth: Int,
        val screenGeometryHeight: Int,
        val screenGeometryX: Int,
        val screenGeometryY: Int,
    )

    private var snapshot: SettingsSnapshot? = null

    fun load() {
        val current = _state.value
        if (current.inSubList) {
            // Lock/unlock or any onResume mid-settings: refresh values without wiping nav
            // state. Keep the existing cancel snapshot so revert still points at pre-edit
            // values rather than the just-resumed state.
            val cat = current.activeCategory ?: return
            val items = buildItemsForCategory(cat)
            _state.update { it.copy(categories = buildCategoryList(), items = items) }
            _appSettings.value = readAppSettings()
            return
        }
        snapshot = captureSettings()
        _state.value = State(categories = buildCategoryList(), categoryIndex = 0)
        _appSettings.value = readAppSettings()
    }

    fun reinitialize(pm: PackageManager, pkgName: String, cr: CollectionsRepository? = null) {
        packageManager = pm
        appPackageName = pkgName
        if (cr != null) collectionsRepository = cr
        invalidateFontOptions()
        if (isTelevision && !settings.batteryDisplaySet) settings.batteryDisplay = BatteryDisplay.HIDE
        load()
    }

    private fun reloadCategories() {
        val current = _state.value
        if (current.inSubList) return
        _state.update { it.copy(categories = buildCategoryList()) }
    }

    private fun buildCategoryList(): List<Category> = buildList {
        addAll(allCategories)
    }

    fun save() {
        snapshot = captureSettings()
    }

    fun cancel() {
        snapshot?.let { restoreSettings(it) }
        _appSettings.value = readAppSettings()
    }

    fun moveSelection(delta: Int) {
        _state.update { current ->
            if (current.inSubList) {
                if (current.items.isEmpty()) return@update current
                val size = current.items.size
                val raw = current.selectedIndex + delta
                val newIndex = ((raw % size) + size) % size
                current.copy(selectedIndex = newIndex)
            } else {
                if (current.categories.isEmpty()) return@update current
                val size = current.categories.size
                val raw = current.categoryIndex + delta
                val newIndex = ((raw % size) + size) % size
                current.copy(categoryIndex = newIndex)
            }
        }
    }

    fun setCategoryIndex(index: Int) {
        _state.update { it.copy(categoryIndex = index) }
    }

    fun enterCategory(): Boolean {
        val current = _state.value
        if (current.inSubList) return false
        val cat = current.categories.getOrNull(current.categoryIndex) ?: return false
        if (cat.key == SettingsCategory.DISPLAY) invalidateFontOptions()
        val items = buildItemsForCategory(cat.key)
        _state.update {
            it.copy(activeCategory = cat.key, activeCategoryLabel = cat.labelRes, items = items, selectedIndex = 0)
        }
        return true
    }

    fun refreshSubList() {
        val current = _state.value
        val cat = current.activeCategory ?: return
        val items = buildItemsForCategory(cat)
        _state.update { it.copy(items = items, selectedIndex = it.selectedIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))) }
    }

    fun refreshItemsAndSettings() {
        val catKey = _state.value.activeCategory
        if (catKey != null) {
            val newItems = buildItemsForCategory(catKey)
            _state.update { it.copy(items = newItems, selectedIndex = it.selectedIndex.coerceAtMost((newItems.size - 1).coerceAtLeast(0))) }
        }
        _appSettings.value = readAppSettings()
    }

    private fun reclampGeometryOffsets() {
        val maxX = (100 - settings.screenGeometryWidth) / 2
        val maxY = (100 - settings.screenGeometryHeight) / 2
        settings.screenGeometryX = settings.screenGeometryX.coerceIn(-maxX, maxX)
        settings.screenGeometryY = settings.screenGeometryY.coerceIn(-maxY, maxY)
    }

    fun resetScreenGeometry() {
        settings.screenGeometryWidth = 100
        settings.screenGeometryHeight = 100
        settings.screenGeometryX = 0
        settings.screenGeometryY = 0
        refreshItemsAndSettings()
    }

    fun enterSubCategory(category: SettingsCategory, @StringRes labelRes: Int, initialIndex: Int = 0) {
        val current = _state.value
        val items = buildItemsForCategory(category)
        val safeInitial = initialIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))
        _state.update {
            it.copy(activeCategory = category, parentCategory = current.activeCategory, parentSelectedIndex = current.selectedIndex, activeCategoryLabel = labelRes, items = items, selectedIndex = safeInitial)
        }
    }

    fun fghPickerInitialIndex(): Int {
        val ids = fghCollections().map { it.id }
        val cur = settings.fghCollectionId ?: return 0
        return ids.indexOf(cur).coerceAtLeast(0)
    }

    fun selectFghCollectionId(id: Long?) {
        settings.fghCollectionId = id
        _appSettings.value = readAppSettings()
    }

    /** Drops back to the category list outright, however deep the user was. The active category
     *  outlives the screen, so without this a flow that ends elsewhere leaves settings reopening
     *  inside whichever sub-list it was abandoned in. */
    fun resetToCategoryList() {
        _state.update {
            it.copy(
                activeCategory = null,
                parentCategory = null,
                parentSelectedIndex = 0,
                activeCategoryLabel = null,
                items = emptyList(),
                selectedIndex = 0,
            )
        }
    }

    fun exitSubList(): Boolean {
        val current = _state.value
        if (!current.inSubList) return false
        val parent = current.parentCategory
        if (parent != null) {
            val parentLabel = current.categories.getOrNull(current.categoryIndex)?.labelRes
            val items = buildItemsForCategory(parent)
            _state.update {
                it.copy(activeCategory = parent, parentCategory = null, parentSelectedIndex = 0, activeCategoryLabel = parentLabel, items = items, selectedIndex = current.parentSelectedIndex)
            }
        } else {
            _state.update {
                it.copy(activeCategory = null, activeCategoryLabel = null, items = emptyList(), selectedIndex = 0)
            }
        }
        return true
    }

    fun cycleSelected(direction: Int, repeatCount: Int = 0, coarse: Boolean = false) {
        val current = _state.value
        if (!current.inSubList) return
        val item = current.items.getOrNull(current.selectedIndex) ?: return

        when (SettingsKey.fromId(item.key)) {
            SettingsKey.TEXT_SIZE -> {
                val entries = TextSize.entries
                val cur = entries.indexOf(settings.textSize).coerceAtLeast(0)
                settings.textSize = entries[((cur + direction) % entries.size + entries.size) % entries.size]
            }
            SettingsKey.FONT -> {
                val cur = fontOptions.indexOfFirst { it.key == settings.font }.coerceAtLeast(0)
                settings.font = fontOptions[((cur + direction) % fontOptions.size + fontOptions.size) % fontOptions.size].key
            }
            SettingsKey.LANGUAGE -> {
                val tags = dev.cannoli.scorza.i18n.LanguageCatalog.ALL.map { it.tag }
                val cur = tags.indexOf(settings.language).coerceAtLeast(0)
                settings.language = tags[((cur + direction) % tags.size + tags.size) % tags.size]
            }
            SettingsKey.SHOW_CLOCK -> {
                if (!settings.showClock) {
                    settings.showClock = true
                    settings.timeFormat = if (direction > 0) TimeFormat.TWELVE_HOUR else TimeFormat.TWENTY_FOUR_HOUR
                } else if (settings.timeFormat == TimeFormat.TWELVE_HOUR && direction > 0) {
                    settings.timeFormat = TimeFormat.TWENTY_FOUR_HOUR
                } else if (settings.timeFormat == TimeFormat.TWENTY_FOUR_HOUR && direction < 0) {
                    settings.timeFormat = TimeFormat.TWELVE_HOUR
                } else {
                    settings.showClock = false
                }
            }
            SettingsKey.ART_WIDTH -> {
                val steps = (35..65 step 5) + 0
                val cur = steps.indexOf(settings.artWidth).coerceAtLeast(0)
                settings.artWidth = steps[((cur + direction) % steps.size + steps.size) % steps.size]
            }
            SettingsKey.ART_SCALE -> {
                val entries = ArtScale.entries
                val cur = entries.indexOf(settings.artScale).coerceAtLeast(0)
                settings.artScale = entries[((cur + direction) % entries.size + entries.size) % entries.size]
            }
            SettingsKey.BG_IMAGE -> cycleBackgroundImage(direction)
            SettingsKey.BG_TINT -> {
                val cur = settings.backgroundTint
                val next = cur + direction * 10
                settings.backgroundTint = when {
                    next > 90 -> 0
                    next < 0 -> 90
                    else -> next
                }
            }
            SettingsKey.SWAP_PLAY_RESUME -> settings.swapPlayResume = !settings.swapPlayResume
            SettingsKey.CONTENT_MODE -> {
                val entries = ContentMode.entries
                val cur = entries.indexOf(settings.contentMode).coerceAtLeast(0)
                settings.contentMode = entries[((cur + direction) % entries.size + entries.size) % entries.size]
            }
            SettingsKey.DEFAULT_VIDEO_DRIVER -> {
                // Auto first, so cycling from a clean install reaches a real driver in one press.
                val entries = listOf("", "gl", "vulkan")
                val cur = entries.indexOf(settings.defaultVideoDriver).coerceAtLeast(0)
                settings.defaultVideoDriver = entries[((cur + direction) % entries.size + entries.size) % entries.size]
            }
            SettingsKey.IGM_SETTINGS_MODE -> {
                val entries = IgmSettingsMode.entries
                val cur = entries.indexOf(settings.igmSettingsMode).coerceAtLeast(0)
                settings.igmSettingsMode = entries[((cur + direction) % entries.size + entries.size) % entries.size]
            }
            SettingsKey.FGH_COLLECTION -> {
                val ids = fghCollections().map { it.id }
                if (ids.isNotEmpty()) {
                    val cur = ids.indexOf(settings.fghCollectionId).coerceAtLeast(0)
                    val next = ((cur + direction) % ids.size + ids.size) % ids.size
                    settings.fghCollectionId = ids[next]
                }
            }
            SettingsKey.SHOW_RECENTLY_PLAYED -> settings.showRecentlyPlayed = !settings.showRecentlyPlayed
            SettingsKey.SHOW_FAVORITES -> settings.showFavorites = !settings.showFavorites
            SettingsKey.SCAN_LIBRARY -> settings.scanLibraryAutomatically = !settings.scanLibraryAutomatically
            SettingsKey.SHOW_WIFI -> settings.showWifi = !settings.showWifi
            SettingsKey.SHOW_BLUETOOTH -> settings.showBluetooth = !settings.showBluetooth
            SettingsKey.SHOW_VPN -> settings.showVpn = !settings.showVpn
            SettingsKey.SHOW_KITCHEN -> settings.showKitchen = !settings.showKitchen
            SettingsKey.SHOW_DOWNLOADS -> settings.showDownloads = !settings.showDownloads
            SettingsKey.SHOW_BATTERY -> {
                val entries = BatteryDisplay.entries
                val cur = entries.indexOf(settings.batteryDisplay).coerceAtLeast(0)
                settings.batteryDisplay = entries[((cur + direction) % entries.size + entries.size) % entries.size]
            }
            SettingsKey.SHOW_UPDATE -> settings.showUpdate = !settings.showUpdate
            SettingsKey.MAIN_MENU_QUIT -> settings.mainMenuQuit = !settings.mainMenuQuit
            SettingsKey.ALWAYS_SAVE_ON_QUIT -> settings.alwaysSaveOnQuit = !settings.alwaysSaveOnQuit
            SettingsKey.PORTRAIT_MARGIN -> {
                // The D-pad stays precise and the shoulders do the travelling: this is measured in
                // pixels, so crossing a few hundred of them one repeat at a time is hopeless, and a
                // ramp fast enough to cover it is too coarse to land on a value.
                val step = when {
                    coarse -> if (repeatCount == 0) 50 else 100
                    repeatCount == 0 -> 1
                    else -> 5
                }
                settings.portraitMarginPx = (settings.portraitMarginPx + direction * step).coerceAtLeast(0)
            }
            SettingsKey.SCREEN_GEO_WIDTH -> {
                val step = if (coarse) 10 else if (repeatCount == 0) 1 else 5
                settings.screenGeometryWidth = (settings.screenGeometryWidth + direction * step).coerceIn(50, 100)
                reclampGeometryOffsets()
            }
            SettingsKey.SCREEN_GEO_HEIGHT -> {
                val step = if (coarse) 10 else if (repeatCount == 0) 1 else 5
                settings.screenGeometryHeight = (settings.screenGeometryHeight + direction * step).coerceIn(50, 100)
                reclampGeometryOffsets()
            }
            SettingsKey.SCREEN_GEO_X -> {
                val step = if (coarse) 10 else if (repeatCount == 0) 1 else 5
                val maxX = (100 - settings.screenGeometryWidth) / 2
                settings.screenGeometryX = (settings.screenGeometryX + direction * step).coerceIn(-maxX, maxX)
            }
            SettingsKey.SCREEN_GEO_Y -> {
                val step = if (coarse) 10 else if (repeatCount == 0) 1 else 5
                val maxY = (100 - settings.screenGeometryHeight) / 2
                settings.screenGeometryY = (settings.screenGeometryY + direction * step).coerceIn(-maxY, maxY)
            }
            SettingsKey.KITCHEN_CODE_BYPASS -> {
                settings.kitchenCodeBypass = !settings.kitchenCodeBypass
                dev.cannoli.scorza.server.KitchenManager.setCodeBypass(settings.kitchenCodeBypass)
            }
            SettingsKey.EXPERIMENTAL_FEATURES -> {
                settings.experimentalFeatures = !settings.experimentalFeatures
            }
            SettingsKey.RELEASE_CHANNEL -> {
                val channels = dev.cannoli.scorza.updater.ReleaseChannel.entries
                val cur = channels.indexOf(settings.releaseChannel).coerceAtLeast(0)
                settings.releaseChannel = channels[((cur + direction) % channels.size + channels.size) % channels.size]
            }
            SettingsKey.ROMM_ALLOW_SELF_SIGNED -> rommStore.allowSelfSigned = !rommStore.allowSelfSigned
            else -> {}
        }

        val activeCategory = current.activeCategory ?: return
        val newItems = buildItemsForCategory(activeCategory)
        _state.update { it.copy(items = newItems, selectedIndex = it.selectedIndex.coerceAtMost((newItems.size - 1).coerceAtLeast(0))) }
        _appSettings.value = readAppSettings()
    }

    fun enterSelected(): String? {
        val current = _state.value
        if (!current.inSubList) return null
        val item = current.items.getOrNull(current.selectedIndex) ?: return null

        return if (item.isEditable) {
            item.key
        } else {
            null
        }
    }

    fun getSelectedItem(): SettingsItem? {
        val current = _state.value
        if (!current.inSubList) return null
        return current.items.getOrNull(current.selectedIndex)
    }

    private fun cycleBackgroundImage(direction: Int = 1) {
        val wallpapersDir = java.io.File(pathsProvider.root, "Wallpapers")
        val imageExtensions = setOf("png", "jpg", "jpeg")
        val images = wallpapersDir.listFiles()
            ?.filter { it.isFile && it.extension.lowercase(java.util.Locale.ROOT) in imageExtensions }
            ?.sortedBy { it.name }
            ?: emptyList()

        if (images.isEmpty()) {
            settings.backgroundImagePath = null
            return
        }

        val currentPath = settings.backgroundImagePath
        val currentIndex = images.indexOfFirst { it.absolutePath == currentPath }

        val newIndex = if (currentIndex == -1) {
            if (direction > 0) 0 else images.lastIndex
        } else {
            val raw = currentIndex + direction
            if (raw < 0 || raw >= images.size) -1 else raw
        }

        settings.backgroundImagePath = if (newIndex < 0) null else images[newIndex].absolutePath
    }

    fun refreshActiveCategory() {
        val catKey = _state.value.activeCategory ?: return
        _state.update { it.copy(items = buildItemsForCategory(catKey)) }
    }

    fun getColorEntries(): List<dev.cannoli.scorza.ui.screens.ColorEntry> {
        val names = mapOf(
            SettingsKey.COLOR_ACCENT.id to R.string.setting_color_accent,
            SettingsKey.COLOR_BACKGROUND.id to R.string.setting_color_background,
            SettingsKey.COLOR_HIGHLIGHT.id to R.string.setting_color_highlight,
            SettingsKey.COLOR_HIGHLIGHT_TEXT.id to R.string.setting_color_highlight_text,
            SettingsKey.COLOR_STATUS_BAR.id to R.string.setting_color_status_bar,
            SettingsKey.COLOR_TEXT.id to R.string.setting_color_text,
            SettingsKey.COLOR_TITLE.id to R.string.setting_color_title
        )
        return names.map { (key, labelRes) ->
            val hex = getColorHex(key)
            val color = hexToColor(hex)
            dev.cannoli.scorza.ui.screens.ColorEntry(
                key = key,
                labelRes = labelRes,
                hex = hex,
                color = dev.cannoli.ui.theme.colorToArgbLong(color ?: androidx.compose.ui.graphics.Color.White)
            )
        }
    }

    fun getColorHex(key: String): String = when (SettingsKey.fromId(key)) {
        SettingsKey.COLOR_HIGHLIGHT -> settings.colorHighlight
        SettingsKey.COLOR_TEXT -> settings.colorText
        SettingsKey.COLOR_HIGHLIGHT_TEXT -> settings.colorHighlightText
        SettingsKey.COLOR_ACCENT -> settings.colorAccent
        SettingsKey.COLOR_TITLE -> settings.colorTitle
        SettingsKey.COLOR_BACKGROUND -> settings.colorBackground
        SettingsKey.COLOR_STATUS_BAR -> settings.colorStatusBar
        else -> "#FFFFFF"
    }

    fun setColor(key: String, hex: String) {
        when (SettingsKey.fromId(key)) {
            SettingsKey.COLOR_HIGHLIGHT -> settings.colorHighlight = hex
            SettingsKey.COLOR_TEXT -> settings.colorText = hex
            SettingsKey.COLOR_HIGHLIGHT_TEXT -> settings.colorHighlightText = hex
            SettingsKey.COLOR_ACCENT -> settings.colorAccent = hex
            SettingsKey.COLOR_TITLE -> settings.colorTitle = hex
            SettingsKey.COLOR_BACKGROUND -> settings.colorBackground = hex
            SettingsKey.COLOR_STATUS_BAR -> settings.colorStatusBar = hex
            else -> {}
        }
        val catKey = _state.value.activeCategory ?: return
        _state.update { it.copy(items = buildItemsForCategory(catKey)) }
        _appSettings.value = readAppSettings()
    }

    private fun captureSettings() = SettingsSnapshot(
        textSize = settings.textSize,
        font = settings.font,
        title = settings.title,
        timeFormat = settings.timeFormat,
        bgImage = settings.backgroundImagePath,
        bgTint = settings.backgroundTint,
        colorHighlight = settings.colorHighlight,
        colorText = settings.colorText,
        colorHighlightText = settings.colorHighlightText,
        colorAccent = settings.colorAccent,
        colorTitle = settings.colorTitle,
        colorBackground = settings.colorBackground,
        colorStatusBar = settings.colorStatusBar,
        swapPlayResume = settings.swapPlayResume,
        showWifi = settings.showWifi,
        showBluetooth = settings.showBluetooth,
        showVpn = settings.showVpn,
        showClock = settings.showClock,
        batteryDisplay = settings.batteryDisplay,
        showRecentlyPlayed = settings.showRecentlyPlayed,
        showFavorites = settings.showFavorites,
        contentMode = settings.contentMode,
        igmSettingsMode = settings.igmSettingsMode,
        fghCollectionId = settings.fghCollectionId,
        sdRoot = settings.sdCardRoot,
        romDirectory = settings.romDirectory,
        toolsName = settings.toolsName,
        portsName = settings.portsName,
        releaseChannel = settings.releaseChannel,
        artWidth = settings.artWidth,
        artScale = settings.artScale,
        portraitMarginPx = settings.portraitMarginPx,
        screenGeometryWidth = settings.screenGeometryWidth,
        screenGeometryHeight = settings.screenGeometryHeight,
        screenGeometryX = settings.screenGeometryX,
        screenGeometryY = settings.screenGeometryY,
    )

    private fun restoreSettings(snap: SettingsSnapshot) {
        settings.textSize = snap.textSize
        settings.font = snap.font
        settings.title = snap.title
        settings.timeFormat = snap.timeFormat
        settings.backgroundImagePath = snap.bgImage
        settings.backgroundTint = snap.bgTint
        settings.colorHighlight = snap.colorHighlight
        settings.colorText = snap.colorText
        settings.colorHighlightText = snap.colorHighlightText
        settings.colorAccent = snap.colorAccent
        settings.colorTitle = snap.colorTitle
        settings.colorBackground = snap.colorBackground
        settings.colorStatusBar = snap.colorStatusBar
        settings.swapPlayResume = snap.swapPlayResume
        settings.showWifi = snap.showWifi
        settings.showBluetooth = snap.showBluetooth
        settings.showVpn = snap.showVpn
        settings.showClock = snap.showClock
        settings.batteryDisplay = snap.batteryDisplay
        settings.showRecentlyPlayed = snap.showRecentlyPlayed
        settings.showFavorites = snap.showFavorites
        settings.contentMode = snap.contentMode
        settings.igmSettingsMode = snap.igmSettingsMode
        settings.fghCollectionId = snap.fghCollectionId
        settings.sdCardRoot = snap.sdRoot
        settings.romDirectory = snap.romDirectory
        settings.toolsName = snap.toolsName
        settings.portsName = snap.portsName
        settings.releaseChannel = snap.releaseChannel
        settings.artWidth = snap.artWidth
        settings.artScale = snap.artScale
        settings.portraitMarginPx = snap.portraitMarginPx
        settings.screenGeometryWidth = snap.screenGeometryWidth
        settings.screenGeometryHeight = snap.screenGeometryHeight
        settings.screenGeometryX = snap.screenGeometryX
        settings.screenGeometryY = snap.screenGeometryY
    }

    private fun fghCollections(): List<CollectionsRepository.CollectionRow> {
        val cr = collectionsRepository ?: return emptyList()
        return cr.all().filter { it.type == CollectionType.STANDARD }
    }

    private fun onOff(value: Boolean) = if (value) R.string.value_on else R.string.value_off
    private fun showHide(value: Boolean) = if (value) R.string.value_show else R.string.value_hide
    private fun buildItemsForCategory(category: SettingsCategory): List<SettingsItem> = when (category) {
        SettingsCategory.GENERAL -> buildList {
            val langLabel = (dev.cannoli.scorza.i18n.LanguageCatalog.byTag(settings.language)
                ?: dev.cannoli.scorza.i18n.LanguageCatalog.ALL.first()).nativeName
            add(SettingsItem(SettingsKey.LANGUAGE.id, R.string.setting_language, valueText = langLabel))
            add(SettingsItem(SettingsKey.TITLE.id, R.string.setting_title, valueText = settings.title.ifEmpty { null }, valueRes = if (settings.title.isEmpty()) R.string.value_none else null, isEditable = true))
            add(SettingsItem(SettingsKey.SWAP_PLAY_RESUME.id, R.string.setting_swap_play_resume, valueRes = onOff(settings.swapPlayResume)))
            add(SettingsItem(SettingsKey.MAIN_MENU_QUIT.id, R.string.setting_main_menu_quit, valueRes = onOff(settings.mainMenuQuit)))
            if (!isTelevision) {
                val launcherLabel = if (isDefaultLauncher()) {
                    R.string.setting_change_default_launcher
                } else {
                    R.string.setting_set_default_launcher
                }
                add(SettingsItem(SettingsKey.SET_DEFAULT_LAUNCHER.id, launcherLabel, isEditable = true))
            }
        }
        SettingsCategory.DISPLAY -> buildList {
            add(SettingsItem(SettingsKey.COLORS.id, R.string.setting_colors, isEditable = true))
            add(SettingsItem(SettingsKey.BG_IMAGE.id, R.string.setting_bg_image, valueText = settings.backgroundImagePath?.let { java.io.File(it).name }, valueRes = if (settings.backgroundImagePath == null) R.string.value_none else null))
            if (settings.backgroundImagePath != null) {
                val tintVal = settings.backgroundTint
                add(SettingsItem(SettingsKey.BG_TINT.id, R.string.setting_bg_tint, valueText = if (tintVal == 0) null else "$tintVal%", valueRes = if (tintVal == 0) R.string.value_off else null))
            }
            val currentFont = fontOptions.firstOrNull { it.key == settings.font } ?: fontOptions.first()
            add(SettingsItem(SettingsKey.FONT.id, R.string.setting_font, valueText = currentFont.label))
            add(SettingsItem(SettingsKey.TEXT_SIZE.id, R.string.setting_text_size, valueText = "${settings.textSize.sp}sp"))
            val artScaleRes = when (settings.artScale) {
                ArtScale.FIT -> R.string.value_fit
                ArtScale.ORIGINAL -> R.string.value_original
                ArtScale.FIT_WIDTH -> R.string.value_fit_width
                ArtScale.FIT_HEIGHT -> R.string.value_fit_height
            }
            add(SettingsItem(SettingsKey.ART_SCALE.id, R.string.setting_art_scale, valueRes = artScaleRes))
            val artW = settings.artWidth
            add(SettingsItem(SettingsKey.ART_WIDTH.id, R.string.setting_art_width, valueText = if (artW == 0) null else "$artW%", valueRes = if (artW == 0) R.string.value_off else null))
            add(SettingsItem(SettingsKey.STATUS_BAR.id, R.string.settings_status_bar, isEditable = true))
            val marginPx = settings.portraitMarginPx
            add(SettingsItem(
                SettingsKey.PORTRAIT_MARGIN.id,
                R.string.setting_portrait_margin,
                valueText = if (marginPx == 0) null else "$marginPx px",
                valueRes = if (marginPx == 0) R.string.value_off else null
            ))
        }
        SettingsCategory.LIBRARY -> buildList {
            val contentModeRes = when (settings.contentMode) {
                ContentMode.PLATFORMS -> R.string.value_platforms
                ContentMode.COLLECTIONS -> R.string.value_collections
                ContentMode.FIVE_GAME_HANDHELD -> R.string.value_five_game_handheld
            }
            add(SettingsItem(SettingsKey.CONTENT_MODE.id, R.string.setting_content_mode, valueRes = contentModeRes))
            if (settings.contentMode == ContentMode.FIVE_GAME_HANDHELD) {
                val rows = fghCollections()
                val curId = settings.fghCollectionId
                val effective = rows.firstOrNull { it.id == curId } ?: rows.firstOrNull()
                if (effective != null && effective.id != curId) {
                    settings.fghCollectionId = effective.id
                }
                add(SettingsItem(
                    SettingsKey.FGH_COLLECTION.id,
                    R.string.setting_fgh_collection,
                    valueText = effective?.displayName,
                    valueRes = if (effective == null) R.string.value_none else null,
                    isEditable = rows.isNotEmpty(),
                    canCycle = rows.isNotEmpty()
                ))
            }
            if (settings.contentMode != ContentMode.FIVE_GAME_HANDHELD) {
                add(SettingsItem(SettingsKey.SHOW_RECENTLY_PLAYED.id, R.string.setting_show_recently_played, valueRes = showHide(settings.showRecentlyPlayed)))
                add(SettingsItem(SettingsKey.SHOW_FAVORITES.id, R.string.setting_show_favorites, valueRes = showHide(settings.showFavorites)))
            }
            if (settings.contentMode == ContentMode.PLATFORMS) {
            }
            add(SettingsItem(SettingsKey.MANAGE_PORTS.id, R.string.setting_manage_ports, isEditable = true))
            add(SettingsItem(SettingsKey.MANAGE_TOOLS.id, R.string.setting_manage_tools, isEditable = true))
            val scanRes = if (settings.scanLibraryAutomatically) R.string.value_automatically else R.string.value_manually
            add(SettingsItem(SettingsKey.SCAN_LIBRARY.id, R.string.setting_scan_library, valueRes = scanRes))
            add(SettingsItem(SettingsKey.SD_ROOT.id, R.string.setting_sd_root, valueText = settings.sdCardRoot, isEditable = true))
            val romDir = settings.romDirectory
            add(SettingsItem(SettingsKey.ROM_DIRECTORY.id, R.string.setting_rom_directory, valueText = romDir.ifEmpty { null }, valueRes = if (romDir.isEmpty()) R.string.value_cannoli_root else null, isEditable = true, canCycle = false))
        }
        SettingsCategory.FGH_COLLECTION_PICKER -> buildList {
            val rows = fghCollections()
            val curId = settings.fghCollectionId
            for (row in rows) {
                add(SettingsItem(
                    key = SettingsKey.FGH_PICK_PREFIX + row.id,
                    labelRes = R.string.setting_fgh_collection,
                    labelText = row.displayName,
                    valueRes = if (row.id == curId) R.string.value_selected else null,
                    isEditable = true,
                    canCycle = false
                ))
            }
        }
        SettingsCategory.COLORS -> listOf(
            SettingsItem(SettingsKey.COLOR_BACKGROUND.id, R.string.setting_color_background, valueText = settings.colorBackground.uppercase(), isEditable = true, swatchColor = hexToColor(settings.colorBackground)),
            SettingsItem(SettingsKey.COLOR_TEXT.id, R.string.setting_color_text, valueText = settings.colorText.uppercase(), isEditable = true, swatchColor = hexToColor(settings.colorText)),
            SettingsItem(SettingsKey.COLOR_STATUS_BAR.id, R.string.setting_color_status_bar, valueText = settings.colorStatusBar.uppercase(), isEditable = true, swatchColor = hexToColor(settings.colorStatusBar)),
            SettingsItem(SettingsKey.COLOR_HIGHLIGHT.id, R.string.setting_color_highlight, valueText = settings.colorHighlight.uppercase(), isEditable = true, swatchColor = hexToColor(settings.colorHighlight)),
            SettingsItem(SettingsKey.COLOR_HIGHLIGHT_TEXT.id, R.string.setting_color_highlight_text, valueText = settings.colorHighlightText.uppercase(), isEditable = true, swatchColor = hexToColor(settings.colorHighlightText)),
            SettingsItem(SettingsKey.COLOR_ACCENT.id, R.string.setting_color_accent, valueText = settings.colorAccent.uppercase(), isEditable = true, swatchColor = hexToColor(settings.colorAccent))
        )
        SettingsCategory.STATUS_BAR -> buildList {
            val batteryRes = when (settings.batteryDisplay) {
                BatteryDisplay.HIDE -> R.string.value_hide
                BatteryDisplay.PERCENT -> R.string.value_percent
                BatteryDisplay.ICON -> R.string.value_icon
            }
            add(SettingsItem(SettingsKey.SHOW_BATTERY.id, R.string.setting_battery, valueRes = batteryRes))
            add(SettingsItem(SettingsKey.SHOW_BLUETOOTH.id, R.string.setting_bluetooth, valueRes = showHide(settings.showBluetooth)))
            add(SettingsItem(SettingsKey.SHOW_CLOCK.id, R.string.setting_clock, valueRes = if (!settings.showClock) R.string.value_hide else if (settings.timeFormat == TimeFormat.TWELVE_HOUR) R.string.value_12h else R.string.value_24h))
            add(SettingsItem(SettingsKey.SHOW_KITCHEN.id, R.string.setting_kitchen_running, valueRes = showHide(settings.showKitchen)))
            add(SettingsItem(SettingsKey.SHOW_DOWNLOADS.id, R.string.setting_downloads_running, valueRes = showHide(settings.showDownloads)))
            add(SettingsItem(SettingsKey.SHOW_UPDATE.id, R.string.setting_updater, valueRes = showHide(settings.showUpdate)))
            add(SettingsItem(SettingsKey.SHOW_VPN.id, R.string.setting_vpn, valueRes = showHide(settings.showVpn)))
            add(SettingsItem(SettingsKey.SHOW_WIFI.id, R.string.setting_wifi, valueRes = showHide(settings.showWifi)))
        }
        SettingsCategory.SCREEN_GEOMETRY -> buildList {
            add(SettingsItem(SettingsKey.SCREEN_GEO_WIDTH.id, R.string.setting_geo_width, valueText = "${settings.screenGeometryWidth}%"))
            add(SettingsItem(SettingsKey.SCREEN_GEO_HEIGHT.id, R.string.setting_geo_height, valueText = "${settings.screenGeometryHeight}%"))
            add(SettingsItem(SettingsKey.SCREEN_GEO_X.id, R.string.setting_geo_hpos, valueText = (if (settings.screenGeometryX >= 0) "+" else "") + settings.screenGeometryX, disabled = settings.screenGeometryWidth >= 100))
            add(SettingsItem(SettingsKey.SCREEN_GEO_Y.id, R.string.setting_geo_vpos, valueText = (if (settings.screenGeometryY >= 0) "+" else "") + settings.screenGeometryY, disabled = settings.screenGeometryHeight >= 100))
        }
        SettingsCategory.INPUT -> listOf(
            SettingsItem(SettingsKey.CONTROLLERS.id, R.string.setting_controllers, isEditable = true),
            SettingsItem(SettingsKey.SHORTCUTS.id, R.string.setting_shortcuts, isEditable = true),
            SettingsItem(SettingsKey.INPUT_TESTER.id, R.string.setting_input_tester, isEditable = true)
        )
        SettingsCategory.EMULATION -> buildList {
            add(SettingsItem(SettingsKey.CORE_MAPPING.id, R.string.setting_emulator_mapping, isEditable = true))
            // No RetroArch package row: which RetroArch runs a platform is part of the mapping
            // itself now, so a global package would only be a second thing claiming that answer.
            //
            // Nor is this row gated on one being installed. It used to be, from when RetroArch was
            // a separate app and its cores were its own; the embedded runner keeps them in
            // filesDir, so Cannoli always has cores to show and the gate only hid them.
            add(SettingsItem(
                SettingsKey.UPDATE_CORES.id,
                R.string.setting_update_cores,
                valueText = settings.lastCoreUpdate.takeIf { it.isNotBlank() }?.let {
                    context.getString(
                        if (settings.lastCoreUpdateCompleted) R.string.setting_update_cores_last
                        else R.string.setting_update_cores_stopped,
                        it,
                    )
                } ?: context.getString(R.string.setting_update_cores_never),
                isEditable = true,
                isAction = true,
                canCycle = false,
            ))
            add(SettingsItem(
                SettingsKey.UPDATE_SHADERS.id,
                R.string.setting_update_shaders,
                valueText = settings.lastShaderUpdate.takeIf { it.isNotBlank() }?.let {
                    context.getString(R.string.setting_update_shaders_last, it)
                } ?: context.getString(R.string.setting_update_shaders_never),
                isEditable = true,
                isAction = true,
                canCycle = false,
            ))
            add(SettingsItem(
                SettingsKey.INSTALLED_CORES.id,
                R.string.setting_installed_cores,
                isEditable = true,
                isAction = true,
                canCycle = false,
            ))
            add(SettingsItem(SettingsKey.ALWAYS_SAVE_ON_QUIT.id, R.string.setting_always_save_on_quit, valueRes = onOff(settings.alwaysSaveOnQuit)))
            add(SettingsItem(SettingsKey.IGM_SETTINGS_MODE.id, R.string.setting_igm_settings_mode, valueRes = when (settings.igmSettingsMode) {
                IgmSettingsMode.CURATED -> R.string.value_igm_mode_curated
                IgmSettingsMode.ALL_SETTINGS -> R.string.value_igm_mode_all_settings
            }))
            add(SettingsItem(SettingsKey.DEFAULT_VIDEO_DRIVER.id, R.string.setting_default_video_driver, valueRes = when (settings.defaultVideoDriver) {
                "gl" -> R.string.value_video_driver_gl
                "vulkan" -> R.string.value_video_driver_vulkan
                else -> R.string.value_automatically
            }))
        }
        SettingsCategory.INTEGRATIONS -> buildList {
            add(SettingsItem(SettingsKey.INTEGRATIONS_RA.id, R.string.settings_retroachievements, isEditable = true))
            add(SettingsItem(SettingsKey.INTEGRATIONS_ROMM.id, R.string.settings_romm, isEditable = true))
        }
        SettingsCategory.RETROACHIEVEMENTS -> buildList {
            add(SettingsItem(SettingsKey.RA_USERNAME.id, R.string.setting_ra_username, valueText = settings.raUsername.ifEmpty { null }, valueRes = if (settings.raUsername.isEmpty()) R.string.value_not_set else null, isEditable = true))
            add(SettingsItem(SettingsKey.RA_PASSWORD.id, R.string.setting_ra_password, valueText = if (raPassword.isEmpty()) null else BULLET.repeat(raPassword.length), valueRes = if (raPassword.isEmpty()) R.string.value_not_set else null, isEditable = true))
            if (settings.raUsername.isNotEmpty() && raPassword.isNotEmpty()) {
                add(SettingsItem(SettingsKey.RA_LOGIN.id, R.string.setting_ra_login, isEditable = true))
            }
        }
        SettingsCategory.ROMM -> buildList {
            if (rommStore.token.isNullOrEmpty()) {
                add(SettingsItem(SettingsKey.ROMM_HOST.id, R.string.setting_romm_host, valueText = rommStore.host.ifEmpty { null }, valueRes = if (rommStore.host.isEmpty()) R.string.value_not_set else null, isEditable = true, canCycle = false))
                add(SettingsItem(SettingsKey.ROMM_ALLOW_SELF_SIGNED.id, R.string.setting_romm_allow_self_signed, valueRes = onOff(rommStore.allowSelfSigned)))
                if (rommStore.host.isNotEmpty()) {
                    add(SettingsItem(SettingsKey.ROMM_PAIR.id, R.string.setting_romm_pair, isEditable = true, canCycle = false))
                    add(SettingsItem(SettingsKey.ROMM_PAIR_CODE.id, R.string.setting_romm_pair_code, isEditable = true, canCycle = false))
                }
            }
        }
        SettingsCategory.ADVANCED -> buildList {
            add(SettingsItem(SettingsKey.LOGGING.id, R.string.setting_logging, isEditable = true))
            add(SettingsItem(SettingsKey.SCREEN_GEOMETRY.id, R.string.setting_screen_geometry, isEditable = true))
            add(SettingsItem(SettingsKey.PERMISSIONS.id, R.string.setting_permissions, isEditable = true))
            add(SettingsItem(SettingsKey.REGENERATE_SYSTEM_FOLDERS.id, R.string.setting_regenerate_system_folders, isEditable = true))
            add(SettingsItem(SettingsKey.RESET_CUSTOM_CONFIG.id, R.string.setting_reset_custom_config, isEditable = true))
            add(SettingsItem(SettingsKey.KITCHEN_CODE_BYPASS.id, R.string.setting_kitchen_code_bypass, valueRes = onOff(settings.kitchenCodeBypass)))
            add(SettingsItem(SettingsKey.EXPERIMENTAL_FEATURES.id, R.string.setting_experimental_features, valueRes = onOff(settings.experimentalFeatures)))
            add(SettingsItem(
                SettingsKey.RELEASE_CHANNEL.id,
                R.string.settings_release_channel,
                valueText = settings.releaseChannel.label
            ))
        }
        SettingsCategory.DEBUG -> listOf(
            SettingsItem(SettingsKey.AUDIT_EMULATOR_INTENTS.id, R.string.setting_audit_emulator_intents, isEditable = true),
            SettingsItem(SettingsKey.ICON_GALLERY.id, R.string.setting_icon_gallery, isEditable = true),
        )
    }
}
