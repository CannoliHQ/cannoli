package dev.cannoli.scorza.ui.viewmodel

import dev.cannoli.scorza.romm.LocalState
import dev.cannoli.scorza.romm.RommGame
import dev.cannoli.scorza.romm.RommGroup
import dev.cannoli.scorza.romm.RommVariantFolder

object RommBrowseFolding {
    fun toRows(groups: List<RommGroup>, presentIds: Set<Int>): List<RommGameRow> =
        groups.map { group ->
            RommGameRow(
                game = group.representative,
                localState = if (group.representative.id in presentIds) LocalState.PRESENT else LocalState.REMOTE,
                members = group.members,
                anyPresent = group.members.any { it.id in presentIds },
            )
        }

    // Folds a mixed-platform game list; per-member present state is resolved through the member's
    // own platform tag (a fold group is single-platform, so the representative's tag drives its lookup).
    fun foldCrossPlatform(
        games: List<RommGame>,
        tagForGame: (RommGame) -> String?,
        localStateForGame: (RommGame, tag: String?) -> LocalState,
    ): List<RommGameRow> =
        RommVariantFolder.foldSorted(games).map { group ->
            val repTag = tagForGame(group.representative)
            RommGameRow(
                game = group.representative,
                localState = localStateForGame(group.representative, repTag),
                members = group.members,
                anyPresent = group.members.any { localStateForGame(it, tagForGame(it)) == LocalState.PRESENT },
            )
        }
}
