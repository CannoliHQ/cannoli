package dev.cannoli.scorza.settings

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.HandlerThread
import org.json.JSONObject
import java.io.File

class SettingsRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("cannoli_settings", Context.MODE_PRIVATE)

    private var json = JSONObject()
    private val jsonLock = Any()
    private var settingsFile: File? = null

    private val saveThread = HandlerThread("settings-save").apply { start() }
    private val saveHandler = Handler(saveThread.looper)
    private val saveRunnable = Runnable { saveToDisk() }

    private inline fun <T> jsonRead(block: JSONObject.() -> T): T = synchronized(jsonLock) { json.block() }
    private inline fun jsonWrite(block: JSONObject.() -> Unit) { synchronized(jsonLock) { json.block() }; scheduleSave() }

    init {
        loadFromDisk()
        migrateFromPrefs()
    }

    private fun loadFromDisk() {
        val file = File(sdCardRoot, "Config/settings.json")
        settingsFile = file
        if (file.exists()) {
            try { synchronized(jsonLock) { json = JSONObject(file.readText()) } } catch (_: java.io.IOException) {} catch (_: org.json.JSONException) {}
        }
    }

    private fun scheduleSave() {
        saveHandler.removeCallbacks(saveRunnable)
        saveHandler.postDelayed(saveRunnable, 100)
    }

    private fun saveToDisk() {
        settingsFile?.let { file ->
            file.parentFile?.mkdirs()
            synchronized(jsonLock) { file.writeText(json.toString(2)) }
        }
    }

    fun flush() {
        saveHandler.removeCallbacks(saveRunnable)
        saveToDisk()
    }

    fun shutdown() {
        flush()
        saveThread.quitSafely()
    }

    private fun migrateFromPrefs() {
        synchronized(jsonLock) { if (json.length() > 0) return }
        val keys = prefs.all.keys - KEY_SD_ROOT
        if (keys.isEmpty()) return
        synchronized(jsonLock) {
            for (key in keys) {
                when (val v = prefs.all[key]) {
                    is String -> json.put(key, v)
                    is Boolean -> json.put(key, v)
                    is Int -> json.put(key, v)
                }
            }
        }
        saveToDisk()
        val editor = prefs.edit()
        for (key in keys) editor.remove(key)
        editor.apply()
    }

    var setupCompleted: Boolean
        get() = prefs.getBoolean(KEY_SETUP_COMPLETED, false)
        set(value) { prefs.edit().putBoolean(KEY_SETUP_COMPLETED, value).apply() }

    var sdCardRoot: String
        get() = prefs.getString(KEY_SD_ROOT, DEFAULT_ROOT) ?: DEFAULT_ROOT
        set(value) {
            prefs.edit().putString(KEY_SD_ROOT, value).apply()
            settingsFile = File(value, "Config/settings.json")
            loadFromDisk()
        }

    var romDirectory: String
        get() = jsonRead { optString(KEY_ROM_DIRECTORY, "") }
        set(value) = jsonWrite { if (value.isEmpty()) remove(KEY_ROM_DIRECTORY) else put(KEY_ROM_DIRECTORY, value) }

    var retroArchPackage: String
        get() = jsonRead { optString(KEY_RA_PACKAGE, DEFAULT_RA_PACKAGE) }
        set(value) = jsonWrite { put(KEY_RA_PACKAGE, value) }

    var textSize: TextSize
        get() = TextSize.fromString(jsonRead { optString(KEY_TEXT_SIZE, null) })
        set(value) = jsonWrite { put(KEY_TEXT_SIZE, value.name) }

    var font: String
        get() = jsonRead { optString(KEY_FONT, "default") }
        set(value) = jsonWrite { put(KEY_FONT, value) }

    var title: String
        get() = jsonRead { optString(KEY_TITLE, "") }
        set(value) = jsonWrite { if (value.isEmpty()) remove(KEY_TITLE) else put(KEY_TITLE, value) }

    var timeFormat: TimeFormat
        get() = TimeFormat.fromString(jsonRead { optString(KEY_TIME_FORMAT, null) })
        set(value) = jsonWrite { put(KEY_TIME_FORMAT, value.name) }

    var backgroundImagePath: String?
        get() = jsonRead { optString(KEY_BG_IMAGE, "").ifEmpty { null } }
        set(value) = jsonWrite { if (value != null) put(KEY_BG_IMAGE, value) else remove(KEY_BG_IMAGE) }

    var graphicsBackend: String
        get() = jsonRead { optString(KEY_GRAPHICS_BACKEND, "GLES") }
        set(value) = jsonWrite { put(KEY_GRAPHICS_BACKEND, value) }

    var platformSwitching: Boolean
        get() = jsonRead { optBoolean(KEY_PLATFORM_SWITCHING, false) }
        set(value) = jsonWrite { put(KEY_PLATFORM_SWITCHING, value) }

    var swapPlayResume: Boolean
        get() = jsonRead { optBoolean(KEY_SWAP_PLAY_RESUME, false) }
        set(value) = jsonWrite { put(KEY_SWAP_PLAY_RESUME, value) }

    var mainMenuQuit: Boolean
        get() = jsonRead { optBoolean(KEY_MAIN_MENU_QUIT, false) }
        set(value) = jsonWrite { put(KEY_MAIN_MENU_QUIT, value) }

    var retroArchDiyMode: Boolean
        get() = jsonRead { optBoolean(KEY_RETROARCH_DIY_MODE, true) }
        set(value) = jsonWrite { put(KEY_RETROARCH_DIY_MODE, value) }

    var showWifi: Boolean
        get() = jsonRead { optBoolean(KEY_SHOW_WIFI, true) }
        set(value) = jsonWrite { put(KEY_SHOW_WIFI, value) }

    var showBluetooth: Boolean
        get() = jsonRead { optBoolean(KEY_SHOW_BLUETOOTH, true) }
        set(value) = jsonWrite { put(KEY_SHOW_BLUETOOTH, value) }

    var showVpn: Boolean
        get() = jsonRead { optBoolean(KEY_SHOW_VPN, false) }
        set(value) = jsonWrite { put(KEY_SHOW_VPN, value) }

    var showClock: Boolean
        get() = jsonRead { optBoolean(KEY_SHOW_CLOCK, true) }
        set(value) = jsonWrite { put(KEY_SHOW_CLOCK, value) }

    var showBattery: Boolean
        get() = jsonRead { optBoolean(KEY_SHOW_BATTERY, true) }
        set(value) = jsonWrite { put(KEY_SHOW_BATTERY, value) }

    var showUpdate: Boolean
        get() = jsonRead { optBoolean(KEY_SHOW_UPDATE, true) }
        set(value) = jsonWrite { put(KEY_SHOW_UPDATE, value) }

    var showEmpty: Boolean
        get() = jsonRead { optBoolean(KEY_SHOW_EMPTY, false) }
        set(value) = jsonWrite { put(KEY_SHOW_EMPTY, value) }

    var showTools: Boolean
        get() = jsonRead { optBoolean(KEY_SHOW_TOOLS, false) }
        set(value) = jsonWrite { put(KEY_SHOW_TOOLS, value) }

    var showPorts: Boolean
        get() = jsonRead { optBoolean(KEY_SHOW_PORTS, false) }
        set(value) = jsonWrite { put(KEY_SHOW_PORTS, value) }

    var showRecentlyPlayed: Boolean
        get() = jsonRead { optBoolean(KEY_SHOW_RECENTLY_PLAYED, true) }
        set(value) = jsonWrite { put(KEY_SHOW_RECENTLY_PLAYED, value) }

    var toolsName: String
        get() = jsonRead { optString(KEY_TOOLS_NAME, "Tools").ifEmpty { "Tools" } }
        set(value) = jsonWrite { if (value == "Tools") remove(KEY_TOOLS_NAME) else put(KEY_TOOLS_NAME, value) }

    var portsName: String
        get() = jsonRead { optString(KEY_PORTS_NAME, "Ports").ifEmpty { "Ports" } }
        set(value) = jsonWrite { if (value == "Ports") remove(KEY_PORTS_NAME) else put(KEY_PORTS_NAME, value) }

    var artWidth: Int
        get() = jsonRead { optInt(KEY_ART_WIDTH, 40) }
        set(value) = jsonWrite { put(KEY_ART_WIDTH, value) }

    var artScale: ArtScale
        get() = ArtScale.fromString(jsonRead { optString(KEY_ART_SCALE, null) })
        set(value) = jsonWrite { put(KEY_ART_SCALE, value.name) }

    var backgroundTint: Int
        get() = jsonRead { optInt(KEY_BG_TINT, 0) }
        set(value) = jsonWrite { put(KEY_BG_TINT, value.coerceIn(0, 90)) }

    var colorHighlight: String
        get() = jsonRead { optString(KEY_COLOR_HIGHLIGHT, "#FFFFFF") }
        set(value) = jsonWrite { put(KEY_COLOR_HIGHLIGHT, value) }

    var colorText: String
        get() = jsonRead { optString(KEY_COLOR_TEXT, "#FFFFFF") }
        set(value) = jsonWrite { put(KEY_COLOR_TEXT, value) }

    var colorHighlightText: String
        get() = jsonRead { optString(KEY_COLOR_HIGHLIGHT_TEXT, "#000000") }
        set(value) = jsonWrite { put(KEY_COLOR_HIGHLIGHT_TEXT, value) }

    var colorAccent: String
        get() = jsonRead { optString(KEY_COLOR_ACCENT, "#FFFFFF") }
        set(value) = jsonWrite { put(KEY_COLOR_ACCENT, value) }

    var raUsername: String
        get() = jsonRead { optString(KEY_RA_USERNAME, "") }
        set(value) = jsonWrite { if (value.isEmpty()) remove(KEY_RA_USERNAME) else put(KEY_RA_USERNAME, value) }

    var raToken: String
        get() = jsonRead { optString(KEY_RA_TOKEN, "") }
        set(value) = jsonWrite { if (value.isEmpty()) remove(KEY_RA_TOKEN) else put(KEY_RA_TOKEN, value) }

    var releaseChannel: String
        get() = jsonRead { optString(KEY_RELEASE_CHANNEL, "STABLE") }
        set(value) = jsonWrite { put(KEY_RELEASE_CHANNEL, value) }

    var lastUpdateCheck: Long
        get() = jsonRead { optLong(KEY_LAST_UPDATE_CHECK, 0L) }
        set(value) = jsonWrite { put(KEY_LAST_UPDATE_CHECK, value) }

    var cachedUpdateVersion: String
        get() = jsonRead { optString(KEY_CACHED_UPDATE_VERSION, "") }
        set(value) = jsonWrite { if (value.isEmpty()) remove(KEY_CACHED_UPDATE_VERSION) else put(KEY_CACHED_UPDATE_VERSION, value) }

    var cachedUpdateCode: Int
        get() = jsonRead { optInt(KEY_CACHED_UPDATE_CODE, 0) }
        set(value) = jsonWrite { if (value == 0) remove(KEY_CACHED_UPDATE_CODE) else put(KEY_CACHED_UPDATE_CODE, value) }

    var cachedUpdateTag: String
        get() = jsonRead { optString(KEY_CACHED_UPDATE_TAG, "") }
        set(value) = jsonWrite { if (value.isEmpty()) remove(KEY_CACHED_UPDATE_TAG) else put(KEY_CACHED_UPDATE_TAG, value) }

    var cachedUpdateApk: String
        get() = jsonRead { optString(KEY_CACHED_UPDATE_APK, "") }
        set(value) = jsonWrite { if (value.isEmpty()) remove(KEY_CACHED_UPDATE_APK) else put(KEY_CACHED_UPDATE_APK, value) }

    var cachedUpdateChangelog: String
        get() = jsonRead { optString(KEY_CACHED_UPDATE_CHANGELOG, "") }
        set(value) = jsonWrite { if (value.isEmpty()) remove(KEY_CACHED_UPDATE_CHANGELOG) else put(KEY_CACHED_UPDATE_CHANGELOG, value) }

    companion object {
        const val DEFAULT_ROOT = "/storage/emulated/0/Cannoli/"
        const val DEFAULT_RA_PACKAGE = "dev.cannoli.ricotta.aarch64"
        val KNOWN_RA_PACKAGES = listOf(
            "dev.cannoli.ricotta.aarch64",
            "dev.cannoli.ricotta",
            "com.retroarch.aarch64",
            "com.retroarch"
        )

        private val RA_PACKAGE_LABELS = mapOf(
            "dev.cannoli.ricotta.aarch64" to "RicottaArch",
            "dev.cannoli.ricotta" to "RicottaArch",
            "com.retroarch.aarch64" to "RetroArch",
            "com.retroarch" to "RetroArch"
        )

        fun getPackageLabel(pkg: String): String = RA_PACKAGE_LABELS[pkg] ?: pkg

        private const val KEY_SETUP_COMPLETED = "setup_completed"
        private const val KEY_SD_ROOT = "sd_root"
        private const val KEY_ROM_DIRECTORY = "rom_directory"
        private const val KEY_RA_PACKAGE = "ra_package"
        private const val KEY_TEXT_SIZE = "text_size"
        private const val KEY_FONT = "font"
        private const val KEY_TITLE = "title"
        private const val KEY_TIME_FORMAT = "time_format"
        private const val KEY_BG_IMAGE = "bg_image"
        private const val KEY_ART_WIDTH = "art_width"
        private const val KEY_ART_SCALE = "art_scale"
        private const val KEY_BG_TINT = "bg_tint"
        private const val KEY_COLOR_HIGHLIGHT = "color_highlight"
        private const val KEY_COLOR_TEXT = "color_text"
        private const val KEY_COLOR_HIGHLIGHT_TEXT = "color_highlight_text"
        private const val KEY_COLOR_ACCENT = "color_accent"
        private const val KEY_PLATFORM_SWITCHING = "platform_switching"
        private const val KEY_SWAP_PLAY_RESUME = "swap_play_resume"
        private const val KEY_MAIN_MENU_QUIT = "main_menu_quit"
        private const val KEY_RETROARCH_DIY_MODE = "retroarch_diy_mode"
        private const val KEY_SHOW_EMPTY = "show_empty"
        private const val KEY_SHOW_WIFI = "show_wifi"
        private const val KEY_SHOW_BLUETOOTH = "show_bluetooth"
        private const val KEY_SHOW_VPN = "show_vpn"
        private const val KEY_SHOW_CLOCK = "show_clock"
        private const val KEY_SHOW_BATTERY = "show_battery"
        private const val KEY_SHOW_UPDATE = "show_update"
        private const val KEY_SHOW_TOOLS = "show_tools"
        private const val KEY_SHOW_PORTS = "show_ports"
        private const val KEY_SHOW_RECENTLY_PLAYED = "show_recently_played"
        private const val KEY_TOOLS_NAME = "tools_name"
        private const val KEY_PORTS_NAME = "ports_name"
        private const val KEY_GRAPHICS_BACKEND = "graphics_backend"
        private const val KEY_RA_USERNAME = "ra_username"
        private const val KEY_RA_TOKEN = "ra_token"
        private const val KEY_RELEASE_CHANNEL = "release_channel"
        private const val KEY_LAST_UPDATE_CHECK = "last_update_check"
        private const val KEY_CACHED_UPDATE_VERSION = "cached_update_version"
        private const val KEY_CACHED_UPDATE_CODE = "cached_update_code"
        private const val KEY_CACHED_UPDATE_TAG = "cached_update_tag"
        private const val KEY_CACHED_UPDATE_APK = "cached_update_apk"
        private const val KEY_CACHED_UPDATE_CHANGELOG = "cached_update_changelog"
    }
}

enum class TextSize(val sp: Int) {
    SP16(16), SP18(18), SP20(20), SP22(22), SP24(24), SP26(26), SP28(28), SP30(30);
    companion object {
        val DEFAULT = SP24
        fun fromString(value: String?): TextSize = when (value) {
            "COMPACT" -> SP16
            "DEFAULT" -> SP24
            else -> entries.firstOrNull { it.name == value } ?: DEFAULT
        }
    }
}

enum class TimeFormat {
    TWELVE_HOUR, TWENTY_FOUR_HOUR;
    companion object {
        fun fromString(value: String?): TimeFormat =
            entries.firstOrNull { it.name == value } ?: TWELVE_HOUR
    }
}

enum class ArtScale {
    FIT, ORIGINAL, FIT_WIDTH, FIT_HEIGHT;
    companion object {
        val DEFAULT = FIT
        fun fromString(value: String?): ArtScale =
            entries.firstOrNull { it.name == value } ?: DEFAULT
    }
}

