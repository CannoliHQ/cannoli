package dev.cannoli.scorza.input.screen

import dagger.hilt.android.scopes.ActivityScoped
import dev.cannoli.scorza.input.ScreenInputHandler
import dev.cannoli.scorza.input.v2.DeviceMapping
import dev.cannoli.scorza.navigation.LauncherScreen
import dev.cannoli.scorza.navigation.NavigationController
import dev.cannoli.scorza.ui.screens.DialogState
import dev.cannoli.scorza.ui.viewmodel.ControllersViewModel
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
        val mapping = resolveMapping(screen) ?: return 0
        // 0 edit buttons, 1 confirm, 2 glyph, 3 exclude, 4 name, 5 reset (when userEdited)
        return if (mapping.userEdited) 6 else 5
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
                gameName = "$RENAME_KEY_PREFIX${mapping.id}",
                currentName = mapping.displayName,
                cursorPos = mapping.displayName.length,
            )
            5 -> if (mapping.userEdited) {
                viewModel.resetMapping(mapping)
                nav.pop()
            }
        }
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
            1 -> applyAndRefresh { viewModel.cycleConfirmButton(mapping) }
            2 -> applyAndRefresh { viewModel.cycleGlyphStyle(mapping, direction) }
            3 -> applyAndRefresh { viewModel.toggleExclude(mapping) }
        }
    }

    private inline fun applyAndRefresh(block: () -> DeviceMapping) {
        val updated = block()
        val connected = viewModel.state.value.connected.map { row ->
            if (row.mapping.id == updated.id) row.copy(mapping = updated) else row
        }
        viewModel.refresh(connected)
    }

    companion object {
        const val RENAME_KEY_PREFIX = "controller_mapping:"
    }
}
