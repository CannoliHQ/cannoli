package dev.cannoli.scorza.input.screen

import dagger.hilt.android.scopes.ActivityScoped
import dev.cannoli.scorza.input.ScreenInputHandler
import dev.cannoli.scorza.navigation.LauncherScreen
import dev.cannoli.scorza.navigation.NavigationController
import dev.cannoli.scorza.ui.viewmodel.ControllersViewModel
import javax.inject.Inject

@ActivityScoped
class ControllersInputHandler @Inject constructor(
    private val nav: NavigationController,
    private val viewModel: ControllersViewModel,
) : ScreenInputHandler {

    private fun current(): LauncherScreen.Controllers? =
        nav.currentScreen as? LauncherScreen.Controllers

    private fun items(): List<Pair<String, Int?>> {
        val s = viewModel.state.value
        return s.connected.map { it.mapping.id to it.androidDeviceId } +
            s.savedMappings.map { it.id to null }
    }

    override fun onUp() {
        val screen = current() ?: return
        val count = items().size
        if (count <= 0) return
        nav.replaceTop(screen.copy(selectedIndex = (screen.selectedIndex - 1).mod(count)))
    }

    override fun onDown() {
        val screen = current() ?: return
        val count = items().size
        if (count <= 0) return
        nav.replaceTop(screen.copy(selectedIndex = (screen.selectedIndex + 1).mod(count)))
    }

    override fun onConfirm() {
        val screen = current() ?: return
        val all = items()
        val selected = all.getOrNull(screen.selectedIndex) ?: return
        nav.push(LauncherScreen.ControllerDetail(mappingId = selected.first, androidDeviceId = selected.second))
    }

    override fun onBack() {
        nav.pop()
    }
}
