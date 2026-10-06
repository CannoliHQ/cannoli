package dev.cannoli.scorza.romm

import java.io.File

object RommHacks {
    const val CATEGORY_GAME = "game"
    const val CATEGORY_HACK = "hack"

    fun topLevelGameFile(game: RommGame): RommFile? =
        game.files.filter { it.category == CATEGORY_GAME && it.isTopLevel }.singleOrNull()

    fun baseFileName(game: RommGame): String? = topLevelGameFile(game)?.fileName

    fun matchNames(game: RommGame): List<String> = listOfNotNull(game.fsName, baseFileName(game))

    fun hackFiles(game: RommGame): List<RommFile> = game.files.filter { it.category == CATEGORY_HACK }

    fun hasHacks(game: RommGame): Boolean = game.files.any { it.category == CATEGORY_HACK }

    fun isHack(file: RommFile): Boolean = file.category == CATEGORY_HACK

    fun baseDownloadFile(game: RommGame): RommFile? =
        if (hasHacks(game)) topLevelGameFile(game) else null

    fun asSingleFile(game: RommGame, file: RommFile): RommGame =
        game.copy(fsName = File(file.fileName).name, sizeBytes = file.sizeBytes, files = listOf(file))
}
