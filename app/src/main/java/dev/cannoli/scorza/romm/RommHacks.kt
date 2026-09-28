package dev.cannoli.scorza.romm

import java.io.File

/**
 * RomM's hacks layout: a game folder holding the base game plus a hacks/ subfolder, reported as
 * one rom whose files carry category "game" or "hack".
 */
object RommHacks {
    const val CATEGORY_GAME = "game"
    const val CATEGORY_HACK = "hack"

    fun topLevelGameFile(game: RommGame): RommFile? =
        game.files.filter { it.category == CATEGORY_GAME && it.isTopLevel }.singleOrNull()

    /** The base game's own file name, the second name a local file may match an entry by. */
    fun baseFileName(game: RommGame): String? = topLevelGameFile(game)?.fileName

    /**
     * Every name a local file may carry to count as [game], in precedence order: the entry's fs
     * name, then its base game file. Hack files never count as the entry.
     */
    fun matchNames(game: RommGame): List<String> = listOfNotNull(game.fsName, baseFileName(game))

    fun hackFiles(game: RommGame): List<RommFile> = game.files.filter { it.category == CATEGORY_HACK }

    fun hasHacks(game: RommGame): Boolean = game.files.any { it.category == CATEGORY_HACK }

    fun isHack(file: RommFile): Boolean = file.category == CATEGORY_HACK

    /** The file a one-file download of [game] fetches, or null when the whole entry downloads. */
    fun baseDownloadFile(game: RommGame): RommFile? =
        if (hasHacks(game)) topLevelGameFile(game) else null

    /** [game] reshaped as a plain single-file game named after [file]. */
    fun asSingleFile(game: RommGame, file: RommFile): RommGame =
        game.copy(fsName = File(file.fileName).name, sizeBytes = file.sizeBytes, files = listOf(file))
}
