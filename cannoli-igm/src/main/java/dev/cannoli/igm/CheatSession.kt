package dev.cannoli.igm

class CheatSession(
    private val manager: CheatManager,
    val file: CheatFile,
    observed: List<RetroArchBridge.CheatRow>,
) {
    data class Row(val cheatIndex: Int, val raIndex: Int, val label: String, val supported: Boolean)

    val rows: List<Row>

    private val enabled = BooleanArray(file.cheats.size)

    init {
        val byIdentity = HashMap<String, ArrayDeque<RetroArchBridge.CheatRow>>()
        for (row in observed) {
            byIdentity.getOrPut(CheatIdentity.of(row.desc, row.code)) { ArrayDeque() }.addLast(row)
        }
        rows = file.cheats.mapIndexed { i, cheat ->
            val match = byIdentity[CheatIdentity.of(cheat.desc, cheat.code)]?.removeFirstOrNull()
            if (match != null && match.enabled) enabled[i] = true
            Row(
                cheatIndex = i,
                raIndex = match?.index ?: -1,
                label = cheat.displayLabel.ifBlank { "Cheat ${i + 1}" },
                supported = match != null && match.supported,
            )
        }
    }

    fun isEnabled(row: Row): Boolean = enabled[row.cheatIndex]

    fun anyEnabled(): Boolean = enabled.any { it }

    fun firstSupportedIndex(): Int = rows.indexOfFirst { it.supported }.coerceAtLeast(0)

    fun toggle(rowIndex: Int): Row? {
        val row = rows.getOrNull(rowIndex) ?: return null
        if (!row.supported) return null
        enabled[row.cheatIndex] = !enabled[row.cheatIndex]
        persist()
        return row
    }

    fun enabledHashes(): Set<String> = rows
        .filter { enabled[it.cheatIndex] }
        .map { identityHash(it) }
        .toSet()

    fun canRestore(hashes: Set<String>): Boolean = rows.any { isRestorable(it, hashes) }

    fun restore(hashes: Set<String>): List<Row> {
        val changed = rows.filter { isRestorable(it, hashes) }
        for (row in changed) enabled[row.cheatIndex] = true
        if (changed.isNotEmpty()) persist()
        return changed
    }

    private fun isRestorable(row: Row, hashes: Set<String>): Boolean =
        row.supported && !enabled[row.cheatIndex] && identityHash(row) in hashes

    fun persist() {
        val hashes = enabledHashes()
        if (hashes.isNotEmpty()) manager.saveLastUsed(file.file.name, hashes)
    }

    private fun identityHash(row: Row): String {
        val cheat = file.cheats[row.cheatIndex]
        return CheatIdentity.hash(cheat.desc, cheat.code)
    }
}
