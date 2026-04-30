package dev.cannoli.scorza.input.screen

import dagger.hilt.android.scopes.ActivityScoped
import dev.cannoli.scorza.input.EditButtonsController
import dev.cannoli.scorza.input.ScreenInputHandler
import dev.cannoli.scorza.input.v2.CanonicalButton
import dev.cannoli.scorza.input.v2.repo.MappingRepository
import dev.cannoli.scorza.navigation.LauncherScreen
import dev.cannoli.scorza.navigation.NavigationController
import dev.cannoli.scorza.ui.viewmodel.ControllersViewModel
import javax.inject.Inject

private val PROTECTED_CANONICALS = setOf(
    CanonicalButton.BTN_UP, CanonicalButton.BTN_DOWN,
    CanonicalButton.BTN_LEFT, CanonicalButton.BTN_RIGHT,
    CanonicalButton.BTN_SOUTH, CanonicalButton.BTN_EAST,
)

@ActivityScoped
class EditButtonsInputHandler @Inject constructor(
    private val nav: NavigationController,
    private val editButtonsController: EditButtonsController,
    private val mappingRepository: MappingRepository,
    private val controllersViewModel: ControllersViewModel,
    private val portRouter: dev.cannoli.scorza.input.v2.runtime.PortRouter,
    private val activeMappingHolder: dev.cannoli.scorza.input.v2.runtime.ActiveMappingHolder,
) : ScreenInputHandler {

    private fun current(): LauncherScreen.EditButtons? =
        nav.currentScreen as? LauncherScreen.EditButtons

    override fun onUp() {
        if (editButtonsController.isListening) return
        val screen = current() ?: return
        val count = CanonicalButton.entries.size
        nav.replaceTop(screen.copy(selectedIndex = (screen.selectedIndex - 1).mod(count)))
    }

    override fun onDown() {
        if (editButtonsController.isListening) return
        val screen = current() ?: return
        val count = CanonicalButton.entries.size
        nav.replaceTop(screen.copy(selectedIndex = (screen.selectedIndex + 1).mod(count)))
    }

    override fun onConfirm() {
        if (editButtonsController.isListening) return
        val screen = current() ?: return
        val canonical = CanonicalButton.entries.getOrNull(screen.selectedIndex) ?: return
        val state = controllersViewModel.state.value
        val mapping = state.connected.firstOrNull { it.mapping.id == screen.mappingId }?.mapping
            ?: state.savedMappings.firstOrNull { it.id == screen.mappingId }
            ?: mappingRepository.findById(screen.mappingId)
            ?: return
        editButtonsController.startListening(mapping, canonical)
        nav.replaceTop(screen.copy(listeningCanonical = canonical))
    }

    override fun onBack() {
        if (editButtonsController.isListening) return
        nav.pop()
    }

    override fun onNorth() {
        if (editButtonsController.isListening) return
        val screen = current() ?: return
        val canonical = CanonicalButton.entries.getOrNull(screen.selectedIndex) ?: return
        if (canonical in PROTECTED_CANONICALS) return
        val state = controllersViewModel.state.value
        val mapping = state.connected.firstOrNull { it.mapping.id == screen.mappingId }?.mapping
            ?: state.savedMappings.firstOrNull { it.id == screen.mappingId }
            ?: mappingRepository.findById(screen.mappingId)
            ?: return
        if (mapping.bindings[canonical].isNullOrEmpty()) return
        val newBindings = mapping.bindings.toMutableMap().apply { this[canonical] = emptyList() }
        val updated = mapping.copy(bindings = newBindings, userEdited = true)
        mappingRepository.save(updated)
        portRouter.updateMapping(updated, rebuildEvaluator = true)
        if (activeMappingHolder.active.value?.id == updated.id) {
            activeMappingHolder.set(updated)
        }
        controllersViewModel.refreshFromRouter()
    }
}
