package dev.cannoli.scorza.ui.screens

import dev.cannoli.scorza.romm.sync.SyncDirection
import dev.cannoli.scorza.romm.sync.SyncHistoryEntry

data class SyncHistoryRow(
    val name: String,
    val direction: SyncDirection,
    val relativeTime: String,
    val detail: String? = null,
)

fun buildHistoryRows(entries: List<SyncHistoryEntry>, now: Long, nowLabel: String): List<SyncHistoryRow> =
    entries.map { e ->
        val secs = ((now - e.createdAt) / 1000).coerceAtLeast(0)
        val rel = when {
            secs < 60 -> nowLabel
            secs < 3600 -> "${secs / 60}m"
            secs < 86_400 -> "${secs / 3600}h"
            else -> "${secs / 86_400}d"
        }
        SyncHistoryRow(e.displayName, e.direction, rel, e.detail)
    }
