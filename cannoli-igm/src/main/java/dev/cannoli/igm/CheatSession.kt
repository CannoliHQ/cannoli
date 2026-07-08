package dev.cannoli.igm

class CheatSession(
    private val manager: CheatManager,
    val files: List<CheatFile>,
    private val hasSystemRam: Boolean,
) {
    data class Row(val fileIndex: Int, val cheatIndex: Int, val label: String, val supported: Boolean)

    private val enabled: List<BooleanArray> = files.map { BooleanArray(it.cheats.size) }
    private val remembered: Map<String, Set<Int>> = manager.loadRemembered()

    val rows: List<Row> = files.flatMapIndexed { fi, file ->
        file.cheats.mapIndexed { ci, cheat ->
            Row(
                fileIndex = fi,
                cheatIndex = ci,
                label = cheat.displayLabel.ifBlank { "Cheat ${ci + 1}" },
                supported = cheat.isEmuHandler || hasSystemRam,
            )
        }
    }

    fun isEnabled(row: Row): Boolean = enabled[row.fileIndex][row.cheatIndex]

    fun anyEnabled(): Boolean = enabled.any { arr -> arr.any { it } }

    fun firstSupportedIndex(): Int = rows.indexOfFirst { it.supported }.coerceAtLeast(0)

    fun toggle(rowIndex: Int): Boolean {
        val row = rows.getOrNull(rowIndex) ?: return false
        if (!row.supported) return false
        enabled[row.fileIndex][row.cheatIndex] = !enabled[row.fileIndex][row.cheatIndex]
        persistIfNonEmpty()
        return true
    }

    fun hasRemembered(): Boolean = files.anyIndexed { fi, f ->
        remembered[f.file.name].orEmpty().any { ci ->
            ci in f.cheats.indices && (f.cheats[ci].isEmuHandler || hasSystemRam)
        }
    }

    fun restoreLastSession(): Int {
        var count = 0
        files.forEachIndexed { fi, f ->
            for (ci in remembered[f.file.name].orEmpty()) {
                if (ci !in f.cheats.indices) continue
                if (!f.cheats[ci].isEmuHandler && !hasSystemRam) continue
                if (!enabled[fi][ci]) {
                    enabled[fi][ci] = true
                    count++
                }
            }
        }
        if (count > 0) persistIfNonEmpty()
        return count
    }

    fun emuCodes(): List<String> = files.flatMapIndexed { fi, f ->
        f.cheats.mapIndexedNotNull { ci, c ->
            if (enabled[fi][ci] && c.isEmuHandler && c.code.isNotBlank()) c.code else null
        }
    }

    fun retroTable(): LongArray {
        val out = LongArray(rows.size * STRIDE)
        var o = 0
        files.forEachIndexed { fi, f ->
            f.cheats.forEachIndexed { ci, c ->
                out[o + 0] = if (enabled[fi][ci]) 1L else 0L
                out[o + 1] = c.handler.toLong()
                out[o + 2] = c.address
                out[o + 3] = c.addressBitPosition
                out[o + 4] = c.value
                out[o + 5] = c.cheatType.toLong()
                out[o + 6] = c.memorySearchSize.toLong()
                out[o + 7] = if (c.bigEndian) 1L else 0L
                out[o + 8] = c.repeatCount
                out[o + 9] = c.repeatAddToValue
                out[o + 10] = c.repeatAddToAddress
                o += STRIDE
            }
        }
        return out
    }

    fun fileSnapshot(): List<Pair<String, Long>> =
        files.map { it.file.name to it.file.lastModified() }

    private fun currentSets(): Map<String, Set<Int>> = files.mapIndexed { fi, f ->
        f.file.name to enabled[fi].withIndex().filter { it.value }.map { it.index }.toSet()
    }.toMap()

    private fun persistIfNonEmpty() {
        val sets = currentSets()
        if (sets.values.any { it.isNotEmpty() }) manager.saveRemembered(sets)
    }

    private inline fun <T> List<T>.anyIndexed(predicate: (Int, T) -> Boolean): Boolean {
        forEachIndexed { i, t -> if (predicate(i, t)) return true }
        return false
    }

    companion object {
        const val STRIDE = 11
    }
}
