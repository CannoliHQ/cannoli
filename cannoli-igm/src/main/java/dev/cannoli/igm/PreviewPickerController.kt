package dev.cannoli.igm

import androidx.compose.runtime.mutableStateOf

/**
 * State behind one live preview picker. One instance per thing being picked.
 *
 * A move applies to the screen directly and stages its key like any other row, so the save prompt
 * leaving the settings tree decides the scope.
 */
class PreviewPickerController {

    /** What this picker is picking, shown on the strip. Set by whoever opens it. */
    val title = mutableStateOf("")

    val items = mutableStateOf<List<String>>(emptyList())

    /** Absolute path of the artwork drawn over the game, null when no overlay is chosen. */
    val activeImage = mutableStateOf<String?>(null)

    /** Name in force, set by the host on attach and kept current as moves are applied. */
    val selected = mutableStateOf<String?>(null)

    /** RetroArch keys an asset move touches, staged into the save prompt. Set by the host. */
    var stagedKeys: Set<String> = emptySet()

    /** Applies an entry to the running emulator. Index is into [items]. */
    var onPreview: ((Int) -> Unit)? = null

    /**
     * Whether this game overrides the platform, and so has an override worth dropping.
     *
     * Drives the legend, which makes the offer double as the answer to where the current value came
     * from: the action appears only when the game is the one deciding.
     */
    val canRestore = mutableStateOf(false)

    /** Drops the game's override and shows what the platform says instead. Set by the host. */
    var onRestoreDefault: (() -> Unit)? = null

    /**
     * Reads the live state the picker shows. Deferred to open time on purpose: RetroArch has no
     * settings system until well after the activity is created, and asking it for one during
     * setup dereferences null inside menu_setting_new.
     */
    var onRefresh: (() -> Unit)? = null

    /** Re-reads live state and returns the index to open on, which is whatever is in force. */
    fun refresh(): Int {
        onRefresh?.invoke()
        return indexOf(selected.value)
    }

    /**
     * Moves by [direction] and applies, wrapping at both ends so a short list can be walked in one
     * direction. Returns the index now showing, unchanged when there is nowhere to go.
     */
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
