package dev.cannoli.igm

class ProviderSettingsController(private val provider: IgmSettingsProvider) {

    enum class Nav { UP, DOWN, LEFT, RIGHT, CONFIRM, BACK, NORTH }

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

    private class Level(val path: List<String>, var cursor: Int)

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

    fun enter(): State {
        levels.clear()
        prompt = null
        promptCursor = 0
        levels.addLast(Level(emptyList(), 0))
        showingDescription = false
        return state()
    }

    fun state(): State {
        prompt?.let { return State.Prompt(it.title, it.options, promptCursor) }
        val level = levels.lastOrNull() ?: return State.Closed
        val screen = provider.screen(level.path)
        // The provider's item list can shrink between events (a cycle that hides
        // gated options, a controller disconnect). Clamp so selectedIndex never
        // points past the end.
        level.cursor = level.cursor.coerceIn(0, (screen.items.size - 1).coerceAtLeast(0))
        val description = if (showingDescription) descriptionOf(screen.items, level.cursor) else null
        return State.Menu(level.path, screen.title, level.cursor, screen.items, description, descriptionScroll)
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
            Nav.UP -> if (count > 0) level.cursor = (level.cursor - 1 + count) % count
            Nav.DOWN -> if (count > 0) level.cursor = (level.cursor + 1) % count
            Nav.LEFT, Nav.RIGHT -> {
                val item = items.getOrNull(level.cursor) as? GenericIgmSettingsItem.Choice ?: return state()
                provider.cycle(item.key, if (button == Nav.LEFT) -1 else 1)
            }
            Nav.CONFIRM -> when (val item = items.getOrNull(level.cursor)) {
                is GenericIgmSettingsItem.Category -> levels.addLast(Level(level.path + item.key, 0))
                is GenericIgmSettingsItem.Action -> {
                    val requested = provider.activate(item.key)
                    if (requested != null) {
                        prompt = requested
                        promptCursor = 0
                        return State.Prompt(requested.title, requested.options, 0)
                    }
                    return State.ActionFired
                }
                else -> {}
            }
            Nav.NORTH -> if (descriptionOf(items, level.cursor) != null) {
                showingDescription = true
                descriptionScroll = 0
            }
            Nav.BACK -> if (levels.size > 1) levels.removeLast() else return exit()
        }
        return state()
    }

    private fun exit(): State = when (val e = provider.exitPrompt()) {
        is IgmSettingsExit.Close -> { levels.clear(); State.Closed }
        is IgmSettingsExit.Prompt -> {
            prompt = e
            promptCursor = 0
            State.Prompt(e.title, e.options, 0)
        }
    }

    private fun onPrompt(button: Nav, p: IgmSettingsExit.Prompt): State {
        val count = p.options.size
        when (button) {
            Nav.UP -> if (count > 0) promptCursor = (promptCursor - 1 + count) % count
            Nav.DOWN -> if (count > 0) promptCursor = (promptCursor + 1) % count
            Nav.CONFIRM -> { p.onChoice(promptCursor); return closeAll() }
            Nav.BACK -> { p.onCancel?.invoke(); return closeAll() }
            else -> {}
        }
        return State.Prompt(p.title, p.options, promptCursor)
    }

    private fun closeAll(): State {
        prompt = null
        levels.clear()
        return State.Closed
    }
}
