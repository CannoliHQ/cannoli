package dev.cannoli.igm

import androidx.compose.runtime.mutableStateOf

class PreviewPickerController {

    val title = mutableStateOf("")

    val items = mutableStateOf<List<String>>(emptyList())

    val activeImage = mutableStateOf<String?>(null)

    val selected = mutableStateOf<String?>(null)

    var stagedKeys: Set<String> = emptySet()

    var onPreview: ((Int) -> Unit)? = null

    val canRestore = mutableStateOf(false)

    var onRestoreDefault: (() -> Unit)? = null

    var onRefresh: (() -> Unit)? = null

    fun refresh(): Int {
        onRefresh?.invoke()
        return indexOf(selected.value)
    }

    fun cycle(current: Int, direction: Int, onWillApply: () -> Unit = {}): Int {
        val size = items.value.size
        if (size == 0) return current
        val next = ((current + direction) % size + size) % size
        if (next == current) return current
        // Before the write, not after: the staging it triggers snapshots the value being replaced.
        onWillApply()
        onPreview?.invoke(next)
        selected.value = items.value.getOrNull(next)
        return next
    }

    fun indexOf(name: String?): Int =
        name?.let { items.value.indexOf(it).takeIf { i -> i >= 0 } } ?: 0
}
