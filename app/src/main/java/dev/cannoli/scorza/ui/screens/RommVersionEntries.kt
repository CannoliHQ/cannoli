package dev.cannoli.scorza.ui.screens

import dev.cannoli.scorza.romm.RommGame
import dev.cannoli.scorza.romm.RommHacks
import dev.cannoli.scorza.util.NaturalSort
import java.io.File

/**
 * The version picker's rows: the sibling entries of [viewed] ([members], present by [presentIds]),
 * then one row per hack file of [viewed], present when a file of that name is in the platform's
 * library ([presentNames], lowercased).
 */
fun rommVersionEntries(
    viewed: RommGame,
    members: List<RommGame>,
    presentIds: Set<Int>,
    presentNames: Set<String>,
): List<RommVariantEntry> {
    val siblings = members.map { g ->
        RommVariantEntry(
            game = g,
            label = g.fsName.substringBeforeLast('.'),
            present = g.id in presentIds,
            isPrimary = g.id == viewed.id,
        )
    }.sortedWith(
        compareByDescending<RommVariantEntry> { it.isPrimary }
            .then(compareBy(NaturalSort) { it.label })
    )
    val hacks = RommHacks.hackFiles(viewed).map { f ->
        RommVariantEntry(
            game = viewed,
            label = File(f.fileName).nameWithoutExtension,
            present = File(f.fileName).name.lowercase() in presentNames,
            isPrimary = false,
            hackFile = f,
        )
    }.sortedWith(compareBy(NaturalSort) { it.label })
    return siblings + hacks
}

fun rommVersionRowCount(versionCount: Int, viewed: RommGame): Int =
    versionCount + RommHacks.hackFiles(viewed).size
