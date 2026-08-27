package dev.cannoli.igm

import androidx.compose.runtime.mutableStateOf

/**
 * State behind the live preview picker.
 *
 * Cannoli draws the artwork itself, so a move changes what is on screen directly rather than asking
 * the emulator for anything. Nothing is persisted here either: a move stages its key the same way
 * cycling any other row does, and the save prompt on the way out of the settings tree decides
 * whether it is written for the platform, for the game, or not at all. That is what gives an
 * overlay the same two scopes every other setting has, rather than a persistence path of its own.
 */
class OverlayPickerController {

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
