package dev.cannoli.igm

class ProviderSettingsController(val provider: IgmSettingsProvider) {

    enum class Nav { UP, DOWN, LEFT, RIGHT, CONFIRM, BACK, NORTH, WEST }

    sealed interface State {
        data class Menu(
            val path: List<String>,
            val title: String,
            val selectedIndex: Int,
            val items: List<GenericIgmSettingsItem>,
            // RetroArch's explanation of the highlighted row, shown instead of the list while set.
            val description: String? = null,
            val descriptionScroll: Int = 0,
        ) : State
        data class Prompt(
            val title: String?,
            val options: List<String>,
            val selectedIndex: Int,
        ) : State
        data object Closed : State
        data object ActionFired : State
    }

    /**
     * [selectedKey] is what the highlight is on; [cursor] is only where that row last sat.
     *
     * RetroArch decides which rows a screen has from the values on it, so a row can appear or
     * vanish under the selection between renders. An index alone then names a different setting,
     * and the next Left or Right cycles whatever slid into that slot.
     *
     * [precedingKey] is the row above it, which for a row that only exists because of another is
     * the one that governs it: RetroArch lists a gated setting directly after its gate.
     */
    private class Level(
        val path: List<String>,
        var cursor: Int,
        var selectedKey: String? = null,
        var precedingKey: String? = null,
    )

    fun descriptionAt(index: Int): String? {
        val level = levels.lastOrNull() ?: return null
        return descriptionOf(provider.screen(level.path).items, index)
    }

    private fun descriptionOf(items: List<GenericIgmSettingsItem>, index: Int): String? =
        (items.getOrNull(index) as? GenericIgmSettingsItem.Choice)?.description

    private val levels = ArrayDeque<Level>()
    private var prompt: IgmSettingsExit.Prompt? = null
    private var promptCursor = 0
    private var showingDescription = false
    // Cumulative scroll steps for the description, signed. The screen scrolls by the delta since it
    // last looked, so repeated presses in one direction keep moving.
    private var descriptionScroll = 0

    fun setOnChanged(callback: () -> Unit) = provider.setOnChanged(callback)

    fun markChangedExternally(keys: Set<String>) = provider.markChangedExternally(keys)

    /** Whether the level on screen has an override to drop, so the legend can offer it. */
    fun canRestoreDefault(): Boolean =
        levels.lastOrNull()?.let { provider.canRestoreDefault(it.path) } ?: false

    /** Whether the highlighted row can be picked up and moved. */
    fun canReorderSelection(): Boolean =
        levels.lastOrNull()?.let { provider.canReorder(it.path, it.cursor) } ?: false

    /**
     * Moves the highlighted row by [delta] and keeps the highlight on it.
     *
     * The cursor follows the row rather than the position, which is the whole feel of dragging:
     * the thing under your thumb is the thing that moves.
     */
    fun reorderSelection(delta: Int): State {
        val level = levels.lastOrNull() ?: return state()
        level.cursor = provider.reorder(level.path, level.cursor, delta)
        return state()
    }

    /** Whether the highlighted row is something the list can take away. */
    fun canRemoveSelection(): Boolean =
        levels.lastOrNull()?.let { provider.canRemoveRow(it.path, it.cursor) } ?: false

    /**
     * Takes the highlighted row out, keeping the cursor in range.
     *
     * Removing the last row would otherwise leave the highlight past the end of a shorter list.
     */
    fun removeSelection(): State {
        val level = levels.lastOrNull() ?: return state()
        provider.removeRow(level.path, level.cursor)
        val count = provider.screen(level.path).items.size
        level.cursor = level.cursor.coerceIn(0, (count - 1).coerceAtLeast(0))
        return state()
    }

    /** Compiles anything built but not yet compiled. */
    fun applyPendingChanges() = provider.applyPendingChanges()

    fun enter(): State {
        levels.clear()
        prompt = null
        promptCursor = 0
        levels.addLast(Level(emptyList(), 0))
        showingDescription = false
        return state()
    }

    fun state(): State {
        prompt?.let { return State.Prompt(it.title, it.labels(), promptCursor) }
        val level = levels.lastOrNull() ?: return State.Closed
        val screen = provider.screen(level.path)
        level.follow(screen.items)
        val description = if (showingDescription) descriptionOf(screen.items, level.cursor) else null
        return State.Menu(level.path, screen.title, level.cursor, screen.items, description, descriptionScroll)
    }

    /**
     * Puts the highlight back on the row it was on, wherever that row now is.
     *
     * A row that has gone takes the selection to the one that preceded it, which is the setting
     * that governs it and so the way to bring it back. Only when that is gone too does this fall
     * back to the position, clamped.
     */
    /** Moves the highlight to [index] and records the row it landed on. */
    private fun Level.select(items: List<GenericIgmSettingsItem>, index: Int) {
        cursor = index
        selectedKey = items.getOrNull(index)?.key
        precedingKey = items.getOrNull(index - 1)?.key
    }

    private fun Level.follow(items: List<GenericIgmSettingsItem>) {
        val last = (items.size - 1).coerceAtLeast(0)
        val moved = selectedKey?.let { key -> items.indexOfFirst { it.key == key } } ?: -1
        val fallback = precedingKey?.let { key -> items.indexOfFirst { it.key == key } } ?: -1
        cursor = when {
            moved >= 0 -> moved
            fallback >= 0 -> fallback
            else -> cursor.coerceIn(0, last)
        }
        selectedKey = items.getOrNull(cursor)?.key
        precedingKey = items.getOrNull(cursor - 1)?.key
    }

    // Rows per jump. Fixed rather than the viewport height, which this does not know: the screen
    // owns layout and the navigator owns the cursor, and a constant keeps that boundary.
    private val PAGE = 10

    // Clamped rather than wrapped: paging is for crossing a long list, and wrapping from the top to
    // the end makes it impossible to reach the start by holding a direction.
    private fun pageBy(level: Level, delta: Int, count: Int): State {
        if (count > 0) {
            level.select(provider.screen(level.path).items, (level.cursor + delta).coerceIn(0, count - 1))
        }
        return state()
    }

    fun onNav(button: Nav): State {
        prompt?.let { return onPrompt(button, it) }
        val level = levels.lastOrNull() ?: return State.Closed
        val items = provider.screen(level.path).items
        val count = items.size
        // The description covers the list, so the list stops taking input. Anything but BACK would
        // move a selection the user cannot see, and BACK is the only way out.
        if (showingDescription) {
            when (button) {
                Nav.BACK -> showingDescription = false
                // A long sublabel does not fit, so Up and Down scroll it. Everything else is
                // ignored: the list underneath is covered and must not take input.
                Nav.UP -> descriptionScroll--
                Nav.DOWN -> descriptionScroll++
                else -> {}
            }
            return state()
        }
        when (button) {
            Nav.UP -> if (count > 0) level.select(items, (level.cursor - 1 + count) % count)
            Nav.DOWN -> if (count > 0) level.select(items, (level.cursor + 1) % count)
            // Clamped rather than wrapped: a page jump is for crossing a long list, and wrapping
            // from the top to the end makes it impossible to reach the start by holding a shoulder.
            // A shader category can hold a hundred presets, which is the reason this exists.

            Nav.LEFT, Nav.RIGHT -> {
                // Left and Right cycle a row that has a value, and page the list when the row has
                // none. A shader browser is folders and presets, so nothing there cycles and paging
                // is what the D-pad means everywhere else in the app.
                val item = items.getOrNull(level.cursor) as? GenericIgmSettingsItem.Choice
                    ?: return pageBy(level, if (button == Nav.LEFT) -PAGE else PAGE, count)
                provider.cycle(item.key, if (button == Nav.LEFT) -1 else 1)
            }
            Nav.CONFIRM -> when (val item = items.getOrNull(level.cursor)) {
                is GenericIgmSettingsItem.Category -> levels.addLast(Level(level.path + item.key, 0))
                is GenericIgmSettingsItem.Action -> {
                    val requested = provider.activate(item.key)
                    if (requested != null) {
                        prompt = requested
                        promptCursor = 0
                        return State.Prompt(requested.title, requested.labels(), 0)
                    }
                    provider.returnPathAfter(item.key, level.path)?.let { target ->
                        // Unwound to the level that asked the question, leaving its cursor where it
                        // was. Nothing is dropped when the path is not on the stack.
                        if (levels.any { it.path == target }) {
                            while (levels.size > 1 && levels.last().path != target) levels.removeLast()
                            return state()
                        }
                    }
                    return State.ActionFired
                }
                else -> {}
            }
            Nav.NORTH -> if (descriptionOf(items, level.cursor) != null) {
                showingDescription = true
                descriptionScroll = 0
            }
            Nav.WEST -> provider.restoreDefault(level.path)
            Nav.BACK -> if (levels.size > 1) levels.removeLast() else return exit()
        }
        return state()
    }

    private fun exit(): State = when (val e = provider.exitPrompt()) {
        is IgmSettingsExit.Close -> { levels.clear(); State.Closed }
        is IgmSettingsExit.Prompt -> {
            prompt = e
            promptCursor = 0
            State.Prompt(e.title, e.labels(), 0)
        }
    }

    private fun IgmSettingsExit.Prompt.labels(): List<String> = options.map { it.label }

    private fun onPrompt(button: Nav, p: IgmSettingsExit.Prompt): State {
        val count = p.options.size
        when (button) {
            Nav.UP -> if (count > 0) promptCursor = (promptCursor - 1 + count) % count
            Nav.DOWN -> if (count > 0) promptCursor = (promptCursor + 1) % count
            Nav.CONFIRM -> { p.options.getOrNull(promptCursor)?.choose(); return closeAll() }
            Nav.BACK -> { p.onCancel?.invoke(); return closeAll() }
            else -> {}
        }
        return State.Prompt(p.title, p.labels(), promptCursor)
    }

    private fun closeAll(): State {
        prompt = null
        levels.clear()
        return State.Closed
    }
}
