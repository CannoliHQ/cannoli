package dev.cannoli.scorza.sigil

/**
 * How a platform lays its save artifacts out, straight from sigil. EXACT names one artifact,
 * PREFIX means a game owns every artifact whose name starts with the id, and SPLIT means the id is
 * already a relative path with directories in it rather than a flat name.
 */
enum class SaveUsage { FOLDER_EXACT, FOLDER_PREFIX, FILE_EXACT, FILE_PREFIX, FOLDER_SPLIT }

enum class IdSource { BINARY, FILENAME }

data class GameId(
    val titleId: String,
    val saveId: String,
    val rawSerial: String,
    val usage: SaveUsage,
    val source: IdSource,
    val experimental: Boolean,
)

sealed interface GameIdStatus {
    /** The drain has not reached this rom yet, which is the common state on the first pass. */
    data object Pending : GameIdStatus
    data object NotFound : GameIdStatus
    data class Found(val id: GameId) : GameIdStatus
}
