package dev.cannoli.scorza.ui.viewmodel

import android.content.Intent
import android.content.pm.PackageManager
import androidx.annotation.StringRes
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import dev.cannoli.scorza.R
import dev.cannoli.scorza.launcher.InstalledCoreService
import dev.cannoli.scorza.settings.ArtScale
import dev.cannoli.scorza.settings.ContentMode
import dev.cannoli.scorza.settings.SettingsRepository
import dev.cannoli.scorza.settings.TextSize
import dev.cannoli.scorza.settings.TimeFormat
import dev.cannoli.scorza.util.FontNameParser
import dev.cannoli.scorza.util.sortedNatural
import dev.cannoli.ui.BULLET
import dev.cannoli.ui.ButtonLabelSet
import dev.cannoli.ui.ConfirmButton
import dev.cannoli.ui.theme.BPReplay
import dev.cannoli.ui.theme.MPlus1Code
import dev.cannoli.ui.theme.hexToColor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

class SettingsViewModel(
    private val settings: SettingsRepository,
    private var cannoliRoot: java.io.File? = null,
    private var packageManager: PackageManager? = null,
    private var appPackageName: String? = null
) {

    val isTelevision: Boolean
        get() = packageManager?.hasSystemFeature(PackageManager.FEATURE_LEANBACK) == true

    private fun isDefaultLauncher(): Boolean {
        val pm = packageManager ?: return false
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolved = pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        return resolved?.activityInfo?.packageName == appPackageName
    }

    data class FontOption(val key: String, val label: String, val fontFamily: FontFamily)

    private var fontOptions: List<FontOption> = buildFontOptions()

    private fun buildFontOptions(): List<FontOption> = buildList {
        add(FontOption("default", "Default", MPlus1Code))
        add(FontOption("the_og", "The OG", BPReplay))
        val fontsDir = cannoliRoot?.let { java.io.File(it, "Config/Fonts") }
        val exts = setOf("ttf", "otf")
        val customFiles = fontsDir?.listFiles()
            ?.filter { it.isFile && it.extension.lowercase(java.util.Locale.ROOT) in exts }
            ?: emptyList()
        for (file in customFiles.sortedNatural { it.name }) {
            val typeface = try { android.graphics.Typeface.createFromFile(file) } catch (_: Exception) { null } ?: continue
            val family = FontFamily(androidx.compose.ui.text.font.Typeface(typeface))
            val label = FontNameParser.getFamilyName(file) ?: file.nameWithoutExtension
            add(FontOption(file.name, label, family))
        }
    }

    private fun resolveFont(): FontFamily {
        val key = settings.font
        return fontOptions.firstOrNull { it.key == key }?.fontFamily ?: MPlus1Code
    }

    data class SettingsItem(
        val key: String,
        @param:StringRes val labelRes: Int,
        val labelText: String? = null,
        @param:StringRes val valueRes: Int? = null,
        val valueText: String? = null,
        val isEditable: Boolean = false,
        val canCycle: Boolean = true,
        val swatchColor: Color? = null
    )

    data class Category(
        val key: String,
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
        val activeCategory: String? = null,
        val parentCategory: String? = null,
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

        val fontFamily: FontFamily = MPlus1Code,
        val title: String = "",
        val colorHighlight: Color = Color.White,
        val colorText: Color = Color.White,
        val colorHighlightText: Color = Color.Black,
        val colorAccent: Color = Color.White,
        val colorTitle: Color = Color.White,
        val showWifi: Boolean = true,
        val showBluetooth: Boolean = true,
        val showVpn: Boolean = false,
        val showClock: Boolean = true,
        val showBattery: Boolean = true,
        val showUpdate: Boolean = true,
        val swapPlayResume: Boolean = false,
        val mainMenuQuit: Boolean = false,
        val retroArchDiyMode: Boolean = true,
        val artWidth: Int = 40,
        val artScale: ArtScale = ArtScale.DEFAULT,
        val buttonLabelSet: ButtonLabelSet = ButtonLabelSet.PLUMBER,
        val confirmButton: ConfirmButton = ConfirmButton.EAST,
        val contentMode: ContentMode = ContentMode.PLATFORMS,
        val portraitMarginPx: Int = 0,
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
        title = settings.title,
        colorHighlight = hexToColor(settings.colorHighlight) ?: Color.White,
        colorText = hexToColor(settings.colorText) ?: Color.White,
        colorHighlightText = hexToColor(settings.colorHighlightText) ?: Color.Black,
        colorAccent = hexToColor(settings.colorAccent) ?: Color.White,
        colorTitle = hexToColor(settings.colorTitle) ?: Color.White,
        showWifi = settings.showWifi,
        showBluetooth = settings.showBluetooth,
        showVpn = settings.showVpn,
        showClock = settings.showClock,
        showBattery = settings.showBattery && !isTelevision,
        showUpdate = settings.showUpdate,
        swapPlayResume = settings.swapPlayResume,
        mainMenuQuit = settings.mainMenuQuit,
        retroArchDiyMode = settings.retroArchDiyMode,
        artWidth = settings.artWidth,
        artScale = settings.artScale,
        buttonLabelSet = settings.buttonLabelSet,
        confirmButton = settings.confirmButton,
        contentMode = settings.contentMode,
        portraitMarginPx = settings.portraitMarginPx,
    )

    private val allCategories = listOf(
        Category("display", R.string.settings_display),
        Category("library", R.string.settings_library),
        Category("input", R.string.settings_input),
        Category("kitchen", R.string.settings_kitchen),
        Category("retroachievements", R.string.settings_retroachievements),
        Category("advanced", R.string.settings_advanced),
        Category("about", R.string.settings_about)
    )

    private fun detectInstalledRaPackages(): List<String> {
        val pm = packageManager ?: return listOf(settings.retroArchPackage)
        return pm.getInstalledPackages(0)
            .map { it.packageName }
            .filter { it.startsWith("com.retroarch") || it.startsWith("dev.cannoli.ricotta") }
    }

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
        val graphicsBackend: String,
        val platformSwitching: Boolean,
        val swapPlayResume: Boolean,
        val showWifi: Boolean,
        val showBluetooth: Boolean,
        val showVpn: Boolean,
        val showClock: Boolean,
        val showBattery: Boolean,
        val showEmpty: Boolean,
        val showRecentlyPlayed: Boolean,
        val contentMode: ContentMode,
        val sdRoot: String,
        val romDirectory: String,
        val raPackage: String,
        val toolsName: String,
        val portsName: String,
        val releaseChannel: String,
        val artWidth: Int,
        val artScale: ArtScale,
        val retroArchDiyMode: Boolean,
        val portraitMarginPx: Int,
    )

    private var snapshot: SettingsSnapshot? = null

    fun load() {
        snapshot = captureSettings()
        _state.value = State(categories = buildCategoryList(), categoryIndex = 0)
        _appSettings.value = readAppSettings()
    }

    fun reinitialize(root: java.io.File, pm: PackageManager, pkgName: String) {
        cannoliRoot = root
        packageManager = pm
        appPackageName = pkgName
        fontOptions = buildFontOptions()
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
        if (cat.key == "display") fontOptions = buildFontOptions()
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
        _state.update { it.copy(items = items) }
    }

    fun refreshAppSettings() {
        _appSettings.value = readAppSettings()
    }

    fun enterSubCategory(key: String, @StringRes labelRes: Int) {
        val current = _state.value
        val items = buildItemsForCategory(key)
        _state.update {
            it.copy(activeCategory = key, parentCategory = current.activeCategory, parentSelectedIndex = current.selectedIndex, activeCategoryLabel = labelRes, items = items, selectedIndex = 0)
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

    fun cycleSelected(direction: Int, repeatCount: Int = 0) {
        val current = _state.value
        if (!current.inSubList) return
        val item = current.items.getOrNull(current.selectedIndex) ?: return

        when (item.key) {
            "text_size" -> {
                val entries = TextSize.entries
                val cur = entries.indexOf(settings.textSize).coerceAtLeast(0)
                settings.textSize = entries[((cur + direction) % entries.size + entries.size) % entries.size]
            }
            "button_labels" -> {
                val entries = ButtonLabelSet.entries
                val cur = entries.indexOf(settings.buttonLabelSet).coerceAtLeast(0)
                settings.buttonLabelSet = entries[((cur + direction) % entries.size + entries.size) % entries.size]
            }
            "confirm_button" -> {
                val entries = ConfirmButton.entries
                val cur = entries.indexOf(settings.confirmButton).coerceAtLeast(0)
                settings.confirmButton = entries[((cur + direction) % entries.size + entries.size) % entries.size]
            }
            "font" -> {
                val cur = fontOptions.indexOfFirst { it.key == settings.font }.coerceAtLeast(0)
                settings.font = fontOptions[((cur + direction) % fontOptions.size + fontOptions.size) % fontOptions.size].key
            }
            "show_clock" -> {
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
            "art_width" -> {
                val steps = (35..65 step 5) + 0
                val cur = steps.indexOf(settings.artWidth).coerceAtLeast(0)
                settings.artWidth = steps[((cur + direction) % steps.size + steps.size) % steps.size]
            }
            "art_scale" -> {
                val entries = ArtScale.entries
                val cur = entries.indexOf(settings.artScale).coerceAtLeast(0)
                settings.artScale = entries[((cur + direction) % entries.size + entries.size) % entries.size]
            }
            "bg_image" -> cycleBackgroundImage(direction)
            "bg_tint" -> {
                val cur = settings.backgroundTint
                val next = cur + direction * 10
                settings.backgroundTint = when {
                    next > 90 -> 0
                    next < 0 -> 90
                    else -> next
                }
            }
            "platform_switching" -> settings.platformSwitching = !settings.platformSwitching
            "swap_play_resume" -> settings.swapPlayResume = !settings.swapPlayResume
            "content_mode" -> {
                val entries = ContentMode.entries
                val cur = entries.indexOf(settings.contentMode).coerceAtLeast(0)
                settings.contentMode = entries[((cur + direction) % entries.size + entries.size) % entries.size]
            }
            "show_empty" -> settings.showEmpty = !settings.showEmpty
            "show_recently_played" -> settings.showRecentlyPlayed = !settings.showRecentlyPlayed
            "show_wifi" -> settings.showWifi = !settings.showWifi
            "show_bluetooth" -> settings.showBluetooth = !settings.showBluetooth
            "show_vpn" -> settings.showVpn = !settings.showVpn
            "show_battery" -> settings.showBattery = !settings.showBattery
            "show_update" -> settings.showUpdate = !settings.showUpdate
            "main_menu_quit" -> settings.mainMenuQuit = !settings.mainMenuQuit
            "retroarch_diy_mode" -> settings.retroArchDiyMode = !settings.retroArchDiyMode
            "debug_logging" -> settings.debugLogging = !settings.debugLogging
            "portrait_margin" -> {
                val step = when {
                    repeatCount == 0 -> 1
                    repeatCount < 10 -> 10
                    else -> 25
                }
                settings.portraitMarginPx = (settings.portraitMarginPx + direction * step).coerceAtLeast(0)
            }
            "kitchen_code_bypass" -> {
                settings.kitchenCodeBypass = !settings.kitchenCodeBypass
                dev.cannoli.scorza.server.KitchenManager.setCodeBypass(settings.kitchenCodeBypass)
            }
            "graphics_backend" -> {
                val backends = listOf("GLES", "VULKAN")
                val cur = backends.indexOf(settings.graphicsBackend).coerceAtLeast(0)
                settings.graphicsBackend = backends[((cur + direction) % backends.size + backends.size) % backends.size]
            }
            "ra_package" -> {
                val pkgs = detectInstalledRaPackages()
                if (pkgs.isNotEmpty()) {
                    val cur = pkgs.indexOf(settings.retroArchPackage).coerceAtLeast(0)
                    settings.retroArchPackage = pkgs[((cur + direction) % pkgs.size + pkgs.size) % pkgs.size]
                }
            }
            "release_channel" -> {
                val channels = dev.cannoli.scorza.updater.ReleaseChannel.entries
                val cur = channels.indexOfFirst { it.name == settings.releaseChannel }.coerceAtLeast(0)
                settings.releaseChannel = channels[((cur + direction) % channels.size + channels.size) % channels.size].name
            }
        }

        val catKey = current.activeCategory ?: return
        val newItems = buildItemsForCategory(catKey)
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

    fun getSelectedItemDisplayValue(): String {
        val item = getSelectedItem() ?: return ""
        return item.valueText ?: ""
    }

    private fun cycleBackgroundImage(direction: Int = 1) {
        val root = cannoliRoot ?: return
        val wallpapersDir = java.io.File(root, "Wallpapers")
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

    fun clearRomDirectory() {
        settings.romDirectory = ""
        val catKey = _state.value.activeCategory ?: return
        _state.update { it.copy(items = buildItemsForCategory(catKey)) }
    }

    fun getColorEntries(): List<dev.cannoli.scorza.ui.screens.ColorEntry> {
        val names = mapOf(
            "color_accent" to R.string.setting_color_accent,
            "color_highlight" to R.string.setting_color_highlight,
            "color_highlight_text" to R.string.setting_color_highlight_text,
            "color_text" to R.string.setting_color_text,
            "color_title" to R.string.setting_color_title
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

    fun getColorHex(key: String): String = when (key) {
        "color_highlight" -> settings.colorHighlight
        "color_text" -> settings.colorText
        "color_highlight_text" -> settings.colorHighlightText
        "color_accent" -> settings.colorAccent
        "color_title" -> settings.colorTitle
        else -> "#FFFFFF"
    }

    fun setColor(key: String, hex: String) {
        when (key) {
            "color_highlight" -> settings.colorHighlight = hex
            "color_text" -> settings.colorText = hex
            "color_highlight_text" -> settings.colorHighlightText = hex
            "color_accent" -> settings.colorAccent = hex
            "color_title" -> settings.colorTitle = hex
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
        graphicsBackend = settings.graphicsBackend,
        platformSwitching = settings.platformSwitching,
        swapPlayResume = settings.swapPlayResume,
        showWifi = settings.showWifi,
        showBluetooth = settings.showBluetooth,
        showVpn = settings.showVpn,
        showClock = settings.showClock,
        showBattery = settings.showBattery,
        showEmpty = settings.showEmpty,
        showRecentlyPlayed = settings.showRecentlyPlayed,
        contentMode = settings.contentMode,
        sdRoot = settings.sdCardRoot,
        romDirectory = settings.romDirectory,
        raPackage = settings.retroArchPackage,
        toolsName = settings.toolsName,
        portsName = settings.portsName,
        releaseChannel = settings.releaseChannel,
        artWidth = settings.artWidth,
        artScale = settings.artScale,
        retroArchDiyMode = settings.retroArchDiyMode,
        portraitMarginPx = settings.portraitMarginPx,
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
        settings.graphicsBackend = snap.graphicsBackend
        settings.platformSwitching = snap.platformSwitching
        settings.swapPlayResume = snap.swapPlayResume
        settings.showWifi = snap.showWifi
        settings.showBluetooth = snap.showBluetooth
        settings.showVpn = snap.showVpn
        settings.showClock = snap.showClock
        settings.showBattery = snap.showBattery
        settings.showEmpty = snap.showEmpty
        settings.showRecentlyPlayed = snap.showRecentlyPlayed
        settings.contentMode = snap.contentMode
        settings.sdCardRoot = snap.sdRoot
        settings.romDirectory = snap.romDirectory
        settings.retroArchPackage = snap.raPackage
        settings.toolsName = snap.toolsName
        settings.portsName = snap.portsName
        settings.releaseChannel = snap.releaseChannel
        settings.artWidth = snap.artWidth
        settings.artScale = snap.artScale
        settings.retroArchDiyMode = snap.retroArchDiyMode
        settings.portraitMarginPx = snap.portraitMarginPx
    }

    private fun onOff(value: Boolean) = if (value) R.string.value_on else R.string.value_off
    private fun showHide(value: Boolean) = if (value) R.string.value_show else R.string.value_hide
    private fun buildItemsForCategory(categoryKey: String): List<SettingsItem> = when (categoryKey) {
        "display" -> buildList {
            val artScaleRes = when (settings.artScale) {
                ArtScale.FIT -> R.string.value_fit
                ArtScale.ORIGINAL -> R.string.value_original
                ArtScale.FIT_WIDTH -> R.string.value_fit_width
                ArtScale.FIT_HEIGHT -> R.string.value_fit_height
            }
            add(SettingsItem("art_scale", R.string.setting_art_scale, valueRes = artScaleRes))
            val artW = settings.artWidth
            add(SettingsItem("art_width", R.string.setting_art_width, valueText = if (artW == 0) null else "$artW%", valueRes = if (artW == 0) R.string.value_off else null))
            add(SettingsItem("bg_image", R.string.setting_bg_image, valueText = settings.backgroundImagePath?.let { java.io.File(it).name }, valueRes = if (settings.backgroundImagePath == null) R.string.value_none else null))
            if (settings.backgroundImagePath != null) {
                val tintVal = settings.backgroundTint
                add(SettingsItem("bg_tint", R.string.setting_bg_tint, valueText = if (tintVal == 0) null else "$tintVal%", valueRes = if (tintVal == 0) R.string.value_off else null))
            }
            add(SettingsItem("colors", R.string.setting_colors, isEditable = true))
            val currentFont = fontOptions.firstOrNull { it.key == settings.font } ?: fontOptions.first()
            add(SettingsItem("font", R.string.setting_font, valueText = currentFont.label))
            val marginPx = settings.portraitMarginPx
            add(SettingsItem(
                "portrait_margin",
                R.string.setting_portrait_margin,
                valueText = if (marginPx == 0) null else "$marginPx px",
                valueRes = if (marginPx == 0) R.string.value_off else null
            ))
            add(SettingsItem("status_bar", R.string.settings_status_bar, isEditable = true))
            add(SettingsItem("text_size", R.string.setting_text_size, valueText = "${settings.textSize.sp}sp"))
            add(SettingsItem("title", R.string.setting_title, valueText = settings.title.ifEmpty { null }, valueRes = if (settings.title.isEmpty()) R.string.value_none else null, isEditable = true))
        }
        "library" -> buildList {
            val contentModeRes = when (settings.contentMode) {
                ContentMode.PLATFORMS -> R.string.value_platforms
                ContentMode.COLLECTIONS -> R.string.value_collections
                ContentMode.FIVE_GAME_HANDHELD -> R.string.value_five_game_handheld
            }
            add(SettingsItem("content_mode", R.string.setting_content_mode, valueRes = contentModeRes))
            if (settings.contentMode != ContentMode.FIVE_GAME_HANDHELD) {
                add(SettingsItem("show_recently_played", R.string.setting_show_recently_played, valueRes = showHide(settings.showRecentlyPlayed)))
            }
            if (settings.contentMode == ContentMode.PLATFORMS) {
                add(SettingsItem("show_empty", R.string.setting_show_empty, valueRes = showHide(settings.showEmpty)))
            }
            add(SettingsItem("manage_ports", R.string.setting_manage_ports, isEditable = true))
            add(SettingsItem("manage_tools", R.string.setting_manage_tools, isEditable = true))
            add(SettingsItem("sd_root", R.string.setting_sd_root, valueText = settings.sdCardRoot, isEditable = true))
            val romDir = settings.romDirectory
            add(SettingsItem("rom_directory", R.string.setting_rom_directory, valueText = romDir.ifEmpty { null }, valueRes = if (romDir.isEmpty()) R.string.value_cannoli_root else null, isEditable = true, canCycle = false))
        }
        "colors" -> listOf(
            SettingsItem("color_text", R.string.setting_color_text, valueText = settings.colorText.uppercase(), isEditable = true, swatchColor = hexToColor(settings.colorText)),
            SettingsItem("color_highlight", R.string.setting_color_highlight, valueText = settings.colorHighlight.uppercase(), isEditable = true, swatchColor = hexToColor(settings.colorHighlight)),
            SettingsItem("color_highlight_text", R.string.setting_color_highlight_text, valueText = settings.colorHighlightText.uppercase(), isEditable = true, swatchColor = hexToColor(settings.colorHighlightText)),
            SettingsItem("color_accent", R.string.setting_color_accent, valueText = settings.colorAccent.uppercase(), isEditable = true, swatchColor = hexToColor(settings.colorAccent))
        )
        "status_bar" -> buildList {
            if (!isTelevision) add(SettingsItem("show_battery", R.string.setting_battery, valueRes = showHide(settings.showBattery)))
            add(SettingsItem("show_bluetooth", R.string.setting_bluetooth, valueRes = showHide(settings.showBluetooth)))
            add(SettingsItem("show_clock", R.string.setting_clock, valueRes = if (!settings.showClock) R.string.value_hide else if (settings.timeFormat == TimeFormat.TWELVE_HOUR) R.string.value_12h else R.string.value_24h))
            add(SettingsItem("show_update", R.string.setting_updater, valueRes = showHide(settings.showUpdate)))
            add(SettingsItem("show_vpn", R.string.setting_vpn, valueRes = showHide(settings.showVpn)))
            add(SettingsItem("show_wifi", R.string.setting_wifi, valueRes = showHide(settings.showWifi)))
        }
        "input" -> listOf(
            SettingsItem("profiles", R.string.setting_profiles, isEditable = true),
            SettingsItem("shortcuts", R.string.setting_shortcuts, isEditable = true),
            SettingsItem("button_labels", R.string.setting_button_labels, valueText = settings.buttonLabelSet.name.lowercase().replaceFirstChar { it.uppercase() }),
            SettingsItem("confirm_button", R.string.setting_confirm_button, valueText = settings.confirmButton.name.lowercase().replaceFirstChar { it.uppercase() }),
            SettingsItem("platform_switching", R.string.setting_platform_switching, valueRes = onOff(settings.platformSwitching)),
            SettingsItem("swap_play_resume", R.string.setting_swap_play_resume, valueRes = onOff(settings.swapPlayResume)),
            SettingsItem("main_menu_quit", R.string.setting_main_menu_quit, valueRes = onOff(settings.mainMenuQuit)),
            SettingsItem("input_tester", R.string.setting_input_tester, isEditable = true)
        )
        "kitchen" -> emptyList()
        "retroachievements" -> buildList {
            add(SettingsItem("ra_username", R.string.setting_ra_username, valueText = settings.raUsername.ifEmpty { null }, valueRes = if (settings.raUsername.isEmpty()) R.string.value_not_set else null, isEditable = true))
            add(SettingsItem("ra_password", R.string.setting_ra_password, valueText = if (raPassword.isEmpty()) null else BULLET.repeat(raPassword.length), valueRes = if (raPassword.isEmpty()) R.string.value_not_set else null, isEditable = true))
            if (settings.raUsername.isNotEmpty() && raPassword.isNotEmpty()) {
                add(SettingsItem("ra_login", R.string.setting_ra_login, isEditable = true))
            }
        }
        "advanced" -> buildList {
            add(SettingsItem("core_mapping", R.string.setting_core_mapping, isEditable = true))
            add(SettingsItem("debug_logging", R.string.setting_debug_logging, valueRes = onOff(settings.debugLogging)))
            add(SettingsItem("retroarch_diy_mode", R.string.setting_retroarch_diy_mode, valueRes = onOff(settings.retroArchDiyMode)))
            add(SettingsItem("kitchen_code_bypass", R.string.setting_kitchen_code_bypass, valueRes = onOff(settings.kitchenCodeBypass)))
            val pkgs = detectInstalledRaPackages()
            if (pkgs.isNotEmpty() && settings.retroArchPackage !in pkgs) {
                settings.retroArchPackage = pkgs.first()
            }
            add(SettingsItem("ra_package", R.string.setting_ra_package, valueText = if (pkgs.isEmpty()) null else settings.retroArchPackage, valueRes = if (pkgs.isEmpty()) R.string.value_none_installed else null, canCycle = pkgs.size > 1))
            if (pkgs.isNotEmpty()) {
                val pkgLabel = InstalledCoreService.getPackageLabel(settings.retroArchPackage)
                add(SettingsItem("installed_cores", R.string.setting_installed_cores, labelText = "$pkgLabel Installed Cores", isEditable = true))
            }
            add(SettingsItem(
                "release_channel",
                R.string.settings_release_channel,
                valueText = dev.cannoli.scorza.updater.ReleaseChannel.fromString(settings.releaseChannel).label
            ))
            if (!isTelevision && !isDefaultLauncher()) {
                add(SettingsItem("set_default_launcher", R.string.setting_set_default_launcher, isEditable = true))
            }
        }
        else -> emptyList()
    }
}
