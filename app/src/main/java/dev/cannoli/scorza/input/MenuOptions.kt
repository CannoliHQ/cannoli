package dev.cannoli.scorza.input

/**
 * Identity for a context menu row, not its label.
 *
 * These used to be the English text and were compared against the rendered string, so the menu could
 * not be translated without breaking selection. The value is a stable key now and the label is looked
 * up when the row is drawn, which leaves every comparison in the handlers working untouched.
 *
 * The key is what crosses into a saved value too, so changing one is a migration, not a rename.
 */
internal const val MENU_RENAME = "menu_rename"
internal const val MENU_DELETE = "menu_delete"
internal const val MENU_DELETE_GAME = "menu_delete_game"
internal const val MENU_DELETE_ART = "menu_delete_art"
internal const val MENU_MANAGE_COLLECTIONS = "menu_manage_collections"
internal const val MENU_EMULATOR_OVERRIDE = "menu_emulator_override"
internal const val MENU_REMOVE_FROM_COLLECTION = "menu_remove_from_collection"
internal const val MENU_CHILD_COLLECTIONS = "menu_child_collections"
internal const val MENU_RA_GAME_ID = "menu_ra_game_id"
internal const val MENU_FORCE_SOFTCORE = "menu_force_softcore"
internal const val MENU_PRELOAD_ACHIEVEMENTS = "menu_preload_achievements"
internal const val MENU_ADD_FAVORITE = "menu_add_favorite"
internal const val MENU_REMOVE_FAVORITE = "menu_remove_favorite"
internal const val MENU_REMOVE = "menu_remove_shortcut"
internal const val MENU_REMOVE_FROM_RECENTS = "menu_remove_from_recents"
internal const val MENU_DOWNLOAD_ART = "menu_download_art"
internal const val MENU_SAVE_SLOTS = "menu_save_slots"
internal const val MENU_RESTORE_BACKUP = "menu_restore_backup"
internal const val MENU_ROMM_SAVES = "menu_romm_saves"
internal const val MENU_GUIDES = "menu_guides"

/** Key to string resource, for the two places a menu row is drawn. */
internal val MENU_LABELS: Map<String, Int> = mapOf(
    MENU_RENAME to dev.cannoli.ui.R.string.menu_rename,
    MENU_DELETE to dev.cannoli.ui.R.string.menu_delete,
    MENU_DELETE_GAME to dev.cannoli.ui.R.string.menu_delete_game,
    MENU_DELETE_ART to dev.cannoli.ui.R.string.menu_delete_art,
    MENU_MANAGE_COLLECTIONS to dev.cannoli.ui.R.string.menu_manage_collections,
    MENU_EMULATOR_OVERRIDE to dev.cannoli.ui.R.string.menu_emulator_override,
    MENU_REMOVE_FROM_COLLECTION to dev.cannoli.ui.R.string.menu_remove_from_collection,
    MENU_CHILD_COLLECTIONS to dev.cannoli.ui.R.string.menu_child_collections,
    MENU_RA_GAME_ID to dev.cannoli.ui.R.string.menu_ra_game_id,
    MENU_FORCE_SOFTCORE to dev.cannoli.ui.R.string.menu_force_softcore,
    MENU_PRELOAD_ACHIEVEMENTS to dev.cannoli.ui.R.string.menu_preload_achievements,
    MENU_ADD_FAVORITE to dev.cannoli.ui.R.string.menu_add_favorite,
    MENU_REMOVE_FAVORITE to dev.cannoli.ui.R.string.menu_remove_favorite,
    MENU_REMOVE to dev.cannoli.ui.R.string.menu_remove_shortcut,
    MENU_REMOVE_FROM_RECENTS to dev.cannoli.ui.R.string.menu_remove_from_recents,
    MENU_DOWNLOAD_ART to dev.cannoli.ui.R.string.menu_download_art,
    MENU_SAVE_SLOTS to dev.cannoli.ui.R.string.menu_save_slots,
    MENU_RESTORE_BACKUP to dev.cannoli.ui.R.string.menu_restore_backup,
    MENU_ROMM_SAVES to dev.cannoli.ui.R.string.menu_romm_saves,
    MENU_GUIDES to dev.cannoli.ui.R.string.menu_guides,
)
