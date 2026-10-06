package dev.cannoli.scorza.sigil

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
    data object Pending : GameIdStatus
    data object NotFound : GameIdStatus
    data class Found(val id: GameId) : GameIdStatus
}
