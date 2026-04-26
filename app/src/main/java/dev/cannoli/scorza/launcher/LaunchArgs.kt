package dev.cannoli.scorza.launcher

import android.content.Intent

data class LaunchArgs(
    val gameTitle: String,
    val corePath: String,
    val romPath: String,
    val originalRomPath: String?,
    val sramPath: String,
    val statePath: String,
    val systemDir: String,
    val saveDir: String,
    val platformTag: String,
    val platformName: String,
    val cannoliRoot: String,
    val colorHighlight: String,
    val colorText: String,
    val colorHighlightText: String,
    val colorAccent: String,
    val colorTitle: String,
    val font: String,
    val debugLogging: Boolean,
    val raUsername: String,
    val raToken: String,
    val raPassword: String,
    val raGameId: Int?,
    val resumeSlot: Int,
) {
    fun writeTo(intent: Intent): Intent = intent.apply {
        putExtra(EXTRA_GAME_TITLE, gameTitle)
        putExtra(EXTRA_CORE_PATH, corePath)
        putExtra(EXTRA_ROM_PATH, romPath)
        if (originalRomPath != null) putExtra(EXTRA_ORIGINAL_ROM_PATH, originalRomPath)
        putExtra(EXTRA_SRAM_PATH, sramPath)
        putExtra(EXTRA_STATE_PATH, statePath)
        putExtra(EXTRA_SYSTEM_DIR, systemDir)
        putExtra(EXTRA_SAVE_DIR, saveDir)
        putExtra(EXTRA_PLATFORM_TAG, platformTag)
        putExtra(EXTRA_PLATFORM_NAME, platformName)
        putExtra(EXTRA_CANNOLI_ROOT, cannoliRoot)
        putExtra(EXTRA_COLOR_HIGHLIGHT, colorHighlight)
        putExtra(EXTRA_COLOR_TEXT, colorText)
        putExtra(EXTRA_COLOR_HIGHLIGHT_TEXT, colorHighlightText)
        putExtra(EXTRA_COLOR_ACCENT, colorAccent)
        putExtra(EXTRA_COLOR_TITLE, colorTitle)
        putExtra(EXTRA_FONT, font)
        putExtra(EXTRA_DEBUG_LOGGING, debugLogging)
        putExtra(EXTRA_RA_USERNAME, raUsername)
        putExtra(EXTRA_RA_TOKEN, raToken)
        putExtra(EXTRA_RA_PASSWORD, raPassword)
        if (raGameId != null) putExtra(EXTRA_RA_GAME_ID, raGameId)
        if (resumeSlot >= 0) putExtra(EXTRA_RESUME_SLOT, resumeSlot)
    }

    companion object {
        private const val EXTRA_GAME_TITLE = "game_title"
        private const val EXTRA_CORE_PATH = "core_path"
        private const val EXTRA_ROM_PATH = "rom_path"
        private const val EXTRA_ORIGINAL_ROM_PATH = "original_rom_path"
        private const val EXTRA_SRAM_PATH = "sram_path"
        private const val EXTRA_STATE_PATH = "state_path"
        private const val EXTRA_SYSTEM_DIR = "system_dir"
        private const val EXTRA_SAVE_DIR = "save_dir"
        private const val EXTRA_PLATFORM_TAG = "platform_tag"
        private const val EXTRA_PLATFORM_NAME = "platform_name"
        private const val EXTRA_CANNOLI_ROOT = "cannoli_root"
        private const val EXTRA_COLOR_HIGHLIGHT = "color_highlight"
        private const val EXTRA_COLOR_TEXT = "color_text"
        private const val EXTRA_COLOR_HIGHLIGHT_TEXT = "color_highlight_text"
        private const val EXTRA_COLOR_ACCENT = "color_accent"
        private const val EXTRA_COLOR_TITLE = "color_title"
        private const val EXTRA_FONT = "font"
        private const val EXTRA_DEBUG_LOGGING = "debug_logging"
        private const val EXTRA_RA_USERNAME = "ra_username"
        private const val EXTRA_RA_TOKEN = "ra_token"
        private const val EXTRA_RA_PASSWORD = "ra_password"
        private const val EXTRA_RA_GAME_ID = "ra_game_id"
        private const val EXTRA_RESUME_SLOT = "resume_slot"

        fun from(intent: Intent): LaunchArgs? {
            val corePath = intent.getStringExtra(EXTRA_CORE_PATH) ?: return null
            val romPath = intent.getStringExtra(EXTRA_ROM_PATH) ?: return null
            val platformTag = intent.getStringExtra(EXTRA_PLATFORM_TAG) ?: ""
            return LaunchArgs(
                gameTitle = intent.getStringExtra(EXTRA_GAME_TITLE) ?: "",
                corePath = corePath,
                romPath = romPath,
                originalRomPath = intent.getStringExtra(EXTRA_ORIGINAL_ROM_PATH),
                sramPath = intent.getStringExtra(EXTRA_SRAM_PATH) ?: "",
                statePath = intent.getStringExtra(EXTRA_STATE_PATH) ?: "",
                systemDir = intent.getStringExtra(EXTRA_SYSTEM_DIR) ?: "",
                saveDir = intent.getStringExtra(EXTRA_SAVE_DIR) ?: "",
                platformTag = platformTag,
                platformName = intent.getStringExtra(EXTRA_PLATFORM_NAME) ?: platformTag,
                cannoliRoot = intent.getStringExtra(EXTRA_CANNOLI_ROOT) ?: "",
                colorHighlight = intent.getStringExtra(EXTRA_COLOR_HIGHLIGHT) ?: "#FFFFFF",
                colorText = intent.getStringExtra(EXTRA_COLOR_TEXT) ?: "#FFFFFF",
                colorHighlightText = intent.getStringExtra(EXTRA_COLOR_HIGHLIGHT_TEXT) ?: "#000000",
                colorAccent = intent.getStringExtra(EXTRA_COLOR_ACCENT) ?: "#FFFFFF",
                colorTitle = intent.getStringExtra(EXTRA_COLOR_TITLE) ?: "#FFFFFF",
                font = intent.getStringExtra(EXTRA_FONT) ?: "default",
                debugLogging = intent.getBooleanExtra(EXTRA_DEBUG_LOGGING, false),
                raUsername = intent.getStringExtra(EXTRA_RA_USERNAME) ?: "",
                raToken = intent.getStringExtra(EXTRA_RA_TOKEN) ?: "",
                raPassword = intent.getStringExtra(EXTRA_RA_PASSWORD) ?: "",
                raGameId = if (intent.hasExtra(EXTRA_RA_GAME_ID)) intent.getIntExtra(EXTRA_RA_GAME_ID, 0) else null,
                resumeSlot = intent.getIntExtra(EXTRA_RESUME_SLOT, -1),
            )
        }
    }
}
