package dev.cannoli.scorza.ui.viewmodel

import dagger.hilt.android.scopes.ActivityScoped
import dev.cannoli.scorza.input.CanonicalButton
import dev.cannoli.scorza.input.ConnectedDevice
import dev.cannoli.scorza.input.DeviceMapping
import dev.cannoli.scorza.input.GlyphStyle
import dev.cannoli.scorza.input.autoconfig.AutoconfigRepository
import dev.cannoli.scorza.input.autoconfig.AutoconfigSeeder
import dev.cannoli.scorza.input.autoconfig.RetroArchCfgEntry
import dev.cannoli.scorza.input.resolver.MappingResolver
import dev.cannoli.scorza.input.resolver.RetroArchAutoconfigImporter
import dev.cannoli.scorza.input.runtime.ActiveMappingHolder
import dev.cannoli.scorza.input.runtime.PortRouter
import dev.cannoli.scorza.util.ErrorLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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
    private val repository: AutoconfigRepository,
    private val portRouter: PortRouter,
    private val activeMappingHolder: ActiveMappingHolder,
    private val resolver: MappingResolver,
    private val seeder: AutoconfigSeeder,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(ControllersUiState())
    val state: StateFlow<ControllersUiState> = _state.asStateFlow()

    init {
        scope.launch {
            portRouter.entrySnapshots.collect { snapshots -> recompute(snapshots) }
        }
    }

    private fun recompute(snapshots: List<PortRouter.Snapshot>) {
        val connectedRows = snapshots.map { snap ->
            ConnectedRow(
                androidDeviceId = snap.androidDeviceId,
                mapping = snap.mapping,
                port = snap.port,
                isBuiltIn = snap.device.vendorId == 0 && snap.device.productId == 0,
            )
        }
        val connectedIds = connectedRows.map { it.mapping.id }.toSet()
        val sortedConnected = connectedRows.sortedWith(
            compareBy<ConnectedRow> { it.port ?: Int.MAX_VALUE }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.mapping.displayName }
        )
        val saved = repository.listEntries()
            .filter { it.isUserOwned }
            .map { RetroArchAutoconfigImporter.import(it, syntheticDevice(it)) }
            .filter { it.id !in connectedIds }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.displayName })
        _state.value = ControllersUiState(connected = sortedConnected, savedMappings = saved)
    }

    // The importer fills a cfg's gaps from the device it was matched against, but a saved profile
    // is listed precisely when its pad is absent, so the cfg has to stand in for its own device.
    private fun syntheticDevice(entry: RetroArchCfgEntry): ConnectedDevice = ConnectedDevice(
        androidDeviceId = -1,
        descriptor = "",
        name = entry.deviceName,
        vendorId = entry.vendorId ?: 0,
        productId = entry.productId ?: 0,
        androidBuildModel = entry.buildModel.orEmpty(),
        sourceMask = entry.sourceMask ?: 0,
        connectedAtMillis = 0L,
    )

    fun mappingById(id: String): DeviceMapping? =
        repository.findById(id)?.let { RetroArchAutoconfigImporter.import(it, syntheticDevice(it)) }

    private fun refreshFromCurrentSnapshots() {
        recompute(portRouter.entrySnapshots.value)
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
        // The seeder writes outside the repository, so the cache has to be dropped again.
        if (seeder.reseedSingle("${mapping.id}.cfg")) {
            repository.invalidate()
        } else {
            // The user's edit is gone either way; a failed restore here means the curated cfg is
            // gone from disk too, with nothing left to fall back on but the runtime default.
            ErrorLog.write("controller reset: failed to restore ${mapping.id}.cfg after deleting the user cfg")
        }
        val connected = portRouter.snapshotEntries().firstOrNull { it.mapping.id == mapping.id }
        if (connected != null) {
            val fresh = resolver.resolve(connected.device)
            // A mapping's id is the cfg it came from, so reset hands the pad a mapping under a
            // different id than the one being replaced. Only the device-keyed swap can do that.
            portRouter.replaceMapping(connected.androidDeviceId, fresh)
            if (activeMappingHolder.active.value?.id == mapping.id) {
                activeMappingHolder.set(fresh)
            }
        } else {
            // Repository changed but the router didn't publish (device wasn't connected),
            // so re-derive savedMappings from the current router snapshot.
            refreshFromCurrentSnapshots()
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
