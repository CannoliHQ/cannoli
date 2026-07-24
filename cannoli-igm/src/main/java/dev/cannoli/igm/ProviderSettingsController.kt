package dev.cannoli.igm

class ProviderSettingsController(private val provider: IgmSettingsProvider) {

    enum class Nav { UP, DOWN, LEFT, RIGHT, CONFIRM, BACK }

    sealed interface State {
        data class Menu(
            val path: List<String>,
            val title: String,
            val selectedIndex: Int,
            val items: List<GenericIgmSettingsItem>,
        ) : State
        data class Prompt(
            val title: String?,
            val options: List<String>,
            val selectedIndex: Int,
        ) : State
        data object Closed : State
    }

    private class Level(val path: List<String>, var cursor: Int)

    private val levels = ArrayDeque<Level>()
    private var prompt: IgmSettingsExit.Prompt? = null
    private var promptCursor = 0

    fun setOnChanged(callback: () -> Unit) = provider.setOnChanged(callback)

    fun enter(): State {
        levels.clear()
        prompt = null
        promptCursor = 0
        levels.addLast(Level(emptyList(), 0))
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
        return State.Menu(level.path, screen.title, level.cursor, screen.items)
    }

    fun onNav(button: Nav): State {
        prompt?.let { return onPrompt(button, it) }
        val level = levels.lastOrNull() ?: return State.Closed
        val items = provider.screen(level.path).items
        val count = items.size
        when (button) {
            Nav.UP -> if (count > 0) level.cursor = (level.cursor - 1 + count) % count
            Nav.DOWN -> if (count > 0) level.cursor = (level.cursor + 1) % count
            Nav.LEFT, Nav.RIGHT -> {
                val item = items.getOrNull(level.cursor) as? GenericIgmSettingsItem.Choice ?: return state()
                provider.cycle(item.key, if (button == Nav.LEFT) -1 else 1)
            }
            Nav.CONFIRM -> when (val item = items.getOrNull(level.cursor)) {
                is GenericIgmSettingsItem.Category -> levels.addLast(Level(level.path + item.key, 0))
                is GenericIgmSettingsItem.Action -> provider.activate(item.key)
                else -> {}
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
            Nav.BACK -> return closeAll()
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
