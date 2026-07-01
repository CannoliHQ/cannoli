package dev.cannoli.scorza.ui.screens

import androidx.annotation.StringRes
import dev.cannoli.scorza.romm.LocalState
import dev.cannoli.scorza.romm.RommGame
import dev.cannoli.ui.R
import java.time.Instant
import java.time.ZoneOffset
import kotlin.math.min

data class MetaRow(@StringRes val labelRes: Int, val value: String)

object RommGameDetailLayout {

    private const val SCALE_REFERENCE = 360f
    private const val SCALE_MIN = 0.85f
    private const val SCALE_MAX = 1.6f

    fun scaleFor(widthDp: Float, heightDp: Float): Float =
        (min(widthDp, heightDp) / SCALE_REFERENCE).coerceIn(SCALE_MIN, SCALE_MAX)

    fun releaseYear(firstReleaseDate: Long?): String? =
        firstReleaseDate?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).year.toString() }

    fun metadataRows(game: RommGame): List<MetaRow> = buildList {
        if (game.companies.isNotEmpty()) add(MetaRow(R.string.romm_detail_publisher, game.companies.joinToString(", ")))
        releaseYear(game.firstReleaseDate)?.let { add(MetaRow(R.string.romm_detail_released, it)) }
        if (game.genres.isNotEmpty()) add(MetaRow(R.string.romm_detail_genre, game.genres.joinToString(", ")))
        if (game.gameModes.isNotEmpty()) add(MetaRow(R.string.romm_detail_players, sortedGameModes(game.gameModes).joinToString(", ")))
        if (game.regions.isNotEmpty()) add(MetaRow(R.string.romm_detail_region, game.regions.joinToString(", ")))
        if (game.languages.isNotEmpty()) add(MetaRow(R.string.romm_detail_languages, game.languages.joinToString(", ")))
        add(MetaRow(R.string.romm_detail_size, formatBytes(game.sizeBytes)))
        if (!game.revision.isNullOrBlank()) add(MetaRow(R.string.romm_detail_revision, game.revision))
    }

    private fun sortedGameModes(modes: List<String>): List<String> =
        modes.sortedBy {
            when (it.lowercase()) {
                "single player" -> 0
                "multiplayer" -> 1
                else -> 2
            }
        }

    fun showDownloadAction(localState: LocalState, downloadEnabled: Boolean, folded: Boolean = false): Boolean =
        downloadEnabled && (localState == LocalState.REMOTE || folded)

    fun formatBytes(bytes: Long): String = when {
        bytes >= 1_073_741_824L -> "%.1f GB".format(bytes / 1_073_741_824.0)
        bytes >= 1_048_576L -> "%.1f MB".format(bytes / 1_048_576.0)
        bytes >= 1_024L -> "%.1f KB".format(bytes / 1_024.0)
        else -> "$bytes B"
    }
}
