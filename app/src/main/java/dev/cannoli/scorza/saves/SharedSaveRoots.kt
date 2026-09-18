package dev.cannoli.scorza.saves

/**
 * Platforms whose core keeps one save root for the whole platform rather than a folder per game.
 *
 * PPSSPP treats the save directory as a memory stick: every game's save lives in `SAVEDATA` beside
 * the stick's own `SYSTEM` and `PPSSPP_STATE`, and a game is identified inside it by the disc id
 * rather than by a directory of its own. Giving each game its own stick would isolate saves that
 * games deliberately read from one another, sequels importing progress and unlock bonuses, and it
 * is not the shape Argosy uses either, so a save written by one launcher would be unreadable by the
 * other.
 *
 * Curated rather than inferred: only a core's own layout says where it files saves, and a directory
 * listing cannot tell a shared root from a game that happens to have subfolders.
 */
object SharedSaveRoots {
    private val ROOTS = mapOf("PSP" to "SAVEDATA")

    /** The directory under the platform's save root that holds id-named save folders. */
    fun subdirFor(tag: String): String? = ROOTS[tag.uppercase()]

    fun isShared(tag: String): Boolean = subdirFor(tag) != null
}
