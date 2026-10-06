package dev.cannoli.igm

class ProviderSettingsController(val provider: IgmSettingsProvider) {

    enum class Nav { UP, DOWN, LEFT, RIGHT, CONFIRM, BACK, HELP, WEST }

    sealed interface State {
        data class Menu(
            val path: List<String>,
            val title: String,
            val selectedIndex: Int,
            val items: List<GenericIgmSettingsItem>,
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

    private class Level(
        val path: List<String>,
        var cursor: Int,
        var selectedKey: String? = null,
        var precedingKey: String? = null,
    )

    private fun descriptionOf(items: List<GenericIgmSettingsItem>, index: Int): String? =
        (items.getOrNull(index) as? GenericIgmSettingsItem.Choice)?.description

    private val levels = ArrayDeque<Level>()
    private var prompt: IgmSettingsExit.Prompt? = null
    private var promptCursor = 0
    private var showingDescription = false
    private var descriptionScroll = 0

    fun setOnChanged(callback: () -> Unit) = provider.setOnChanged(callback)

    fun markChangedExternally(keys: Set<String>) = provider.markChangedExternally(keys)

    fun canRestoreDefault(): Boolean =
        levels.lastOrNull()?.let { provider.canRestoreDefault(it.path) } ?: false

    fun canReorderSelection(): Boolean =
        levels.lastOrNull()?.let { provider.canReorder(it.path, it.cursor) } ?: false

    fun reorderSelection(delta: Int): State {
        val level = levels.lastOrNull() ?: return state()
        level.cursor = provider.reorder(level.path, level.cursor, delta)
        return state()
    }

    fun canRemoveSelection(): Boolean =
        levels.lastOrNull()?.let { provider.canRemoveRow(it.path, it.cursor) } ?: false

    fun canReset(): Boolean =
        levels.lastOrNull()?.let { provider.canReset(it.path) } ?: false

    fun openResetPrompt(): State {
        val level = levels.lastOrNull() ?: return state()
        val requested = provider.resetPrompt(level.path) ?: return state()
        prompt = requested
        promptCursor = 0
        return State.Prompt(requested.title, requested.labels(), 0)
    }

    fun removeSelection(): State {
        val level = levels.lastOrNull() ?: return state()
        provider.removeRow(level.path, level.cursor)
        val count = provider.screen(level.path).items.size
        level.cursor = level.cursor.coerceIn(0, (count - 1).coerceAtLeast(0))
        return state()
    }

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

    private val PAGE = 10

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
        if (showingDescription) {
            when (button) {
                Nav.BACK -> showingDescription = false
                Nav.UP -> descriptionScroll--
                Nav.DOWN -> descriptionScroll++
                else -> {}
            }
            return state()
        }
        when (button) {
            Nav.UP -> if (count > 0) level.select(items, (level.cursor - 1 + count) % count)
            Nav.DOWN -> if (count > 0) level.select(items, (level.cursor + 1) % count)
            Nav.LEFT, Nav.RIGHT -> {
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
                        if (levels.any { it.path == target }) {
                            while (levels.size > 1 && levels.last().path != target) levels.removeLast()
                            return state()
                        }
                    }
                    return State.ActionFired
                }
                else -> {}
            }
            Nav.HELP -> if (descriptionOf(items, level.cursor) != null) {
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
