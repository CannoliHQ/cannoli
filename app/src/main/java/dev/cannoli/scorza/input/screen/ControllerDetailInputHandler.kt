package dev.cannoli.scorza.input.screen

import dagger.hilt.android.scopes.ActivityScoped
import dev.cannoli.scorza.input.ScreenInputHandler
import dev.cannoli.scorza.input.DeviceMapping
import dev.cannoli.scorza.navigation.LauncherScreen
import dev.cannoli.scorza.navigation.NavigationController
import dev.cannoli.scorza.ui.screens.DialogState
import dev.cannoli.scorza.ui.screens.RenameTarget
import dev.cannoli.scorza.ui.viewmodel.ControllersViewModel
import dev.cannoli.ui.components.KeyboardState
import javax.inject.Inject

@ActivityScoped
class ControllerDetailInputHandler @Inject constructor(
    private val nav: NavigationController,
    private val viewModel: ControllersViewModel,
) : ScreenInputHandler {

    private fun current(): LauncherScreen.ControllerDetail? =
        nav.currentScreen as? LauncherScreen.ControllerDetail

    private fun resolveMapping(screen: LauncherScreen.ControllerDetail): DeviceMapping? {
        val s = viewModel.state.value
        return s.connected.firstOrNull { it.mapping.id == screen.mappingId }?.mapping
            ?: s.savedMappings.firstOrNull { it.id == screen.mappingId }
    }

    private fun rowCount(): Int {
        val screen = current() ?: return 0
        resolveMapping(screen) ?: return 0
        // 0 edit buttons, 1 confirm, 2 glyph, 3 exclude, 4 name
        return 5
    }

    override fun onUp() {
        val screen = current() ?: return
        val count = rowCount()
        if (count <= 0) return
        nav.replaceTop(screen.copy(selectedIndex = (screen.selectedIndex - 1).mod(count)))
    }

    override fun onDown() {
        val screen = current() ?: return
        val count = rowCount()
        if (count <= 0) return
        nav.replaceTop(screen.copy(selectedIndex = (screen.selectedIndex + 1).mod(count)))
    }

    override fun onConfirm() {
        val screen = current() ?: return
        val mapping = resolveMapping(screen) ?: return
        when (screen.selectedIndex) {
            0 -> nav.push(LauncherScreen.EditButtons(mappingId = mapping.id))
            4 -> nav.dialogState.value = DialogState.RenameInput(
                target = RenameTarget.ControllerMapping(mapping.id),
                titleRes = dev.cannoli.ui.R.string.keyboard_title_rename_controller,
                keyboard = KeyboardState(text = mapping.displayName, cursorPos = mapping.displayName.length),
            )
        }
    }

    override fun onWest() {
        val screen = current() ?: return
        val mapping = resolveMapping(screen) ?: return
        if (mapping.userEdited) nav.dialogState.value = DialogState.ControllerResetConfirm(mapping.id)
    }

    fun resetConfirmed(mappingId: String) {
        val screen = current()?.takeIf { it.mappingId == mappingId } ?: return
        val mapping = resolveMapping(screen) ?: return
        viewModel.resetMapping(mapping)
        nav.pop()
    }

    override fun onLeft() = cycleSelected(direction = -1)
    override fun onRight() = cycleSelected(direction = 1)

    override fun onBack() {
        nav.pop()
    }

    private fun cycleSelected(direction: Int) {
        val screen = current() ?: return
        val mapping = resolveMapping(screen) ?: return
        when (screen.selectedIndex) {
            1 -> viewModel.cycleConfirmButton(mapping)
            2 -> viewModel.cycleGlyphStyle(mapping, direction)
            3 -> viewModel.toggleExclude(mapping)
        }
    }
}
