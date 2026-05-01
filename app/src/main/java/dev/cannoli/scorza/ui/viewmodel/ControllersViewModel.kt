package dev.cannoli.scorza.ui.viewmodel

import dagger.hilt.android.scopes.ActivityScoped
import dev.cannoli.scorza.input.v2.CanonicalButton
import dev.cannoli.scorza.input.v2.DeviceMapping
import dev.cannoli.scorza.input.v2.GlyphStyle
import dev.cannoli.scorza.input.v2.repo.MappingRepository
import dev.cannoli.scorza.input.v2.resolver.MappingResolver
import dev.cannoli.scorza.input.v2.runtime.ActiveMappingHolder
import dev.cannoli.scorza.input.v2.runtime.PortRouter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

data class ConnectedRow(
    val androidDeviceId: Int,
    val mapping: DeviceMapping,
    val port: Int?,
    val isBuiltIn: Boolean,
)

data class ControllersUiState(
    val connected: List<ConnectedRow> = emptyList(),
    val savedMappings: List<DeviceMapping> = emptyList(),
)

@ActivityScoped
class ControllersViewModel @Inject constructor(
    private val repository: MappingRepository,
    private val portRouter: PortRouter,
    private val activeMappingHolder: ActiveMappingHolder,
    private val resolver: MappingResolver,
) {
    private val _state = MutableStateFlow(ControllersUiState())
    val state: StateFlow<ControllersUiState> = _state.asStateFlow()

    fun refresh(connectedRows: List<ConnectedRow>) {
        val connectedIds = connectedRows.map { it.mapping.id }.toSet()
        val sortedConnected = connectedRows.sortedWith(
            compareBy<ConnectedRow> { it.port ?: Int.MAX_VALUE }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.mapping.displayName }
        )
        val saved = repository.list()
            .filter { it.id !in connectedIds }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.displayName })
        _state.value = ControllersUiState(connected = sortedConnected, savedMappings = saved)
    }

    fun refreshFromRouter() {
        val rows = portRouter.snapshotEntries().map { snap ->
            ConnectedRow(
                androidDeviceId = snap.androidDeviceId,
                mapping = snap.mapping,
                port = snap.port,
                isBuiltIn = snap.device.vendorId == 0 && snap.device.productId == 0,
            )
        }
        refresh(rows)
    }

    fun cycleConfirmButton(mapping: DeviceMapping): DeviceMapping {
        val flipped = mapping.copy(
            menuConfirm = if (mapping.menuConfirm == CanonicalButton.BTN_EAST) CanonicalButton.BTN_SOUTH else CanonicalButton.BTN_EAST,
            menuBack = if (mapping.menuConfirm == CanonicalButton.BTN_EAST) CanonicalButton.BTN_EAST else CanonicalButton.BTN_SOUTH,
            userEdited = true,
        )
        return persist(flipped, rebuildEvaluator = false)
    }

    fun cycleGlyphStyle(mapping: DeviceMapping, direction: Int = 1): DeviceMapping {
        val styles = GlyphStyle.entries
        val size = styles.size
        val cur = styles.indexOf(mapping.glyphStyle).coerceAtLeast(0)
        val next = styles[((cur + direction) % size + size) % size]
        val updated = mapping.copy(glyphStyle = next, userEdited = true)
        return persist(updated, rebuildEvaluator = false)
    }

    fun toggleExclude(mapping: DeviceMapping): DeviceMapping {
        val updated = mapping.copy(excludeFromGameplay = !mapping.excludeFromGameplay, userEdited = true)
        return persist(updated, rebuildEvaluator = false)
    }

    fun renameMapping(mapping: DeviceMapping, newName: String): DeviceMapping {
        val updated = mapping.copy(displayName = newName, userEdited = true)
        return persist(updated, rebuildEvaluator = false)
    }

    fun resetMapping(mapping: DeviceMapping) {
        repository.delete(mapping.id)
        val connected = portRouter.snapshotEntries().firstOrNull { it.mapping.id == mapping.id }
        if (connected != null) {
            val fresh = resolver.resolve(connected.device).mapping
            portRouter.updateMapping(fresh, rebuildEvaluator = true)
            if (activeMappingHolder.active.value?.id == mapping.id) {
                activeMappingHolder.set(fresh)
            }
            refreshFromRouter()
        } else {
            refresh(_state.value.connected)
        }
    }

    private fun persist(mapping: DeviceMapping, rebuildEvaluator: Boolean): DeviceMapping {
        repository.save(mapping)
        portRouter.updateMapping(mapping, rebuildEvaluator = rebuildEvaluator)
        if (activeMappingHolder.active.value?.id == mapping.id) {
            activeMappingHolder.set(mapping)
        }
        return mapping
    }
}
