package dev.cannoli.scorza.ui.components

import dev.cannoli.scorza.R
import dev.cannoli.scorza.ui.screens.RaTokenState
import dev.cannoli.scorza.romm.RommArtType

val ROMM_ADVANCED_ROWS = listOf(R.string.romm_settings_rebuild, R.string.romm_settings_download_art)

enum class RommActionRow(@androidx.annotation.StringRes val labelRes: Int) {
    DOWNLOADS(R.string.romm_download_queue),
    ;
    companion object {
        fun visibleRows(hasDownloads: Boolean): List<RommActionRow> =
            if (hasDownloads) entries else entries.filterNot { it == DOWNLOADS }
    }
}

enum class RaAccountRow(@androidx.annotation.StringRes val labelRes: Int, val isCycle: Boolean = false) {
    ACCOUNT(R.string.achievos_account_row_account),
    HARDCORE(R.string.achievos_account_row_hardcore, isCycle = true),
    OFFLINE_SETS(R.string.achievos_account_row_offline_sets),
}

enum class RommSettingsRow(@androidx.annotation.StringRes val labelRes: Int, val isCycle: Boolean = false) {
    COVER_ART(R.string.romm_settings_cover_art, isCycle = true),
    CONCURRENT(R.string.romm_settings_concurrent, isCycle = true),
    SAVE_SYNC(R.string.setting_romm_save_sync),
    PLATFORMS(R.string.romm_settings_platforms),
    COLLECTIONS(R.string.romm_settings_collections),
    ADVANCED(R.string.romm_settings_advanced),
    SERVER_INFO(R.string.romm_settings_server_info),
}

enum class RommSaveSyncRow {
    TOGGLE, BACKUPS, HISTORY, CONFLICTS, ERRORS, RESTORE;
    companion object {
        fun visibleRows(supported: Boolean, enabled: Boolean, pendingConflicts: Int, syncErrors: Int, hasBackups: Boolean): List<RommSaveSyncRow> =
            buildList {
                add(TOGGLE)
                if (supported && enabled) {
                    add(BACKUPS)
                    add(HISTORY)
                    if (pendingConflicts > 0) add(CONFLICTS)
                    if (syncErrors > 0) add(ERRORS)
                }
                // Restore is a recovery action, so it stays available even when sync is off.
                if (hasBackups) add(RESTORE)
            }
    }
}

@androidx.annotation.StringRes
fun raTokenStatusRes(state: RaTokenState): Int = when (state) {
    RaTokenState.CHECKING -> R.string.achievos_token_checking
    RaTokenState.VALID -> R.string.achievos_token_valid
    RaTokenState.INVALID -> R.string.achievos_token_invalid
    RaTokenState.UNREACHABLE -> R.string.achievos_token_offline
}

@androidx.annotation.StringRes
fun rommArtLabelRes(artType: RommArtType): Int = when (artType) {
    RommArtType.DEFAULT -> R.string.romm_art_default
    RommArtType.NONE -> R.string.romm_art_off
    RommArtType.BOX2D -> R.string.romm_art_box2d
    RommArtType.BOX3D -> R.string.romm_art_box3d
    RommArtType.MIX -> R.string.romm_art_mix
    RommArtType.TITLE -> R.string.romm_art_title
    RommArtType.SCREENSHOT -> R.string.romm_art_screenshot
    RommArtType.MARQUEE -> R.string.romm_art_marquee
}

