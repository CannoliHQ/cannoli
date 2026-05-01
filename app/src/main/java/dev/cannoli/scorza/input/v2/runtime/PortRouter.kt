package dev.cannoli.scorza.input.v2.runtime

import dev.cannoli.scorza.input.v2.AnalogRole
import dev.cannoli.scorza.input.v2.CanonicalButton
import dev.cannoli.scorza.input.v2.ConnectedDevice
import dev.cannoli.scorza.input.v2.DeviceMapping
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class PortRouter(private val maxPorts: Int = 4) {

    private data class Entry(
        val device: ConnectedDevice,
        var mapping: DeviceMapping,
        var port: Int?,
        var evaluator: PortEvaluator,
    )

    private val entries = linkedMapOf<Int, Entry>()
    private val aliases = mutableMapOf<Int, Int>()  // alias id -> primary id
    private var launcherTriggerDeviceId: Int? = null

    private val _routes = MutableStateFlow<Map<Int, Int>>(emptyMap())
    val routes: StateFlow<Map<Int, Int>> = _routes.asStateFlow()

    private fun resolveId(deviceId: Int): Int = aliases[deviceId] ?: deviceId

    fun onConnect(device: ConnectedDevice, mapping: DeviceMapping) {
        if (entries.containsKey(device.androidDeviceId)) return
        entries[device.androidDeviceId] = Entry(device, mapping, port = null, evaluator = PortEvaluator(mapping))
        recompute()
    }

    fun onDisconnect(androidDeviceId: Int) {
        if (aliases.remove(androidDeviceId) != null) return
        entries.remove(androidDeviceId)
        // Drop any aliases that pointed to this primary id.
        aliases.entries.removeAll { it.value == androidDeviceId }
        if (launcherTriggerDeviceId == androidDeviceId) launcherTriggerDeviceId = null
        recompute()
    }

    fun addAlias(primaryId: Int, aliasId: Int) {
        if (primaryId == aliasId) return
        if (!entries.containsKey(primaryId)) return
        // Clean up any prior entry for the alias id so we don't double-track it.
        if (entries.containsKey(aliasId)) {
            entries.remove(aliasId)
            recompute()
        }
        aliases[aliasId] = primaryId
    }

    fun aliasesFor(primaryId: Int): Set<Int> =
        aliases.entries.filter { it.value == primaryId }.map { it.key }.toSet()

    fun aliasesSnapshot(): Map<Int, Int> = aliases.toMap()

    fun removeAlias(aliasId: Int) {
        aliases.remove(aliasId)
    }

    /**
     * Move the entry at [oldPrimary] to [newPrimary]. Aliases that pointed to [oldPrimary]
     * are re-pointed to [newPrimary] (except [newPrimary] itself, which becomes a real entry
     * and is removed from the alias map). Used when the kernel removes the original
     * androidDeviceId of a controller we'd already aliased a phantom onto -- promoting the
     * surviving phantom to primary keeps the controller bound to its port.
     */
    fun promoteAlias(oldPrimary: Int, newPrimary: Int) {
        if (oldPrimary == newPrimary) return
        val entry = entries.remove(oldPrimary) ?: return
        val rewired = entry.copy(device = entry.device.copy(androidDeviceId = newPrimary))
        entries[newPrimary] = rewired
        if (launcherTriggerDeviceId == oldPrimary) launcherTriggerDeviceId = newPrimary
        // Re-point or remove aliases.
        val toRepoint = aliases.entries.filter { it.value == oldPrimary }.map { it.key }
        for (aliasId in toRepoint) {
            if (aliasId == newPrimary) aliases.remove(aliasId)
            else aliases[aliasId] = newPrimary
        }
        publish()
    }

    fun markLaunchTrigger(androidDeviceId: Int) {
        launcherTriggerDeviceId = androidDeviceId
        recompute()
    }

    fun portFor(androidDeviceId: Int): Int? = entries[resolveId(androidDeviceId)]?.port

    fun reassign(deviceId: Int, toPort: Int) {
        if (toPort < 0 || toPort >= maxPorts) return
        val target = entries[deviceId] ?: return
        val displaced = entries.values.firstOrNull { it.port == toPort && it != target }
        val previous = target.port
        target.port = toPort
        displaced?.port = previous
        publish()
    }

    private fun recompute() {
        for (entry in entries.values) entry.port = null

        val ordered = entries.values
            .sortedBy { it.device.connectedAtMillis }
            .toMutableList()
        val launchEntry = launcherTriggerDeviceId?.let { entries[it] }

        val externalPresent = entries.values.any { !it.device.isBuiltIn && !it.mapping.excludeFromGameplay }
        val externalLaunched = launchEntry != null && !launchEntry.device.isBuiltIn

        val occupied = BooleanArray(maxPorts)

        if (launchEntry != null && !launchEntry.mapping.excludeFromGameplay) {
            launchEntry.port = 0
            occupied[0] = true
        }

        for (entry in ordered) {
            if (entry == launchEntry) continue
            if (entry.mapping.excludeFromGameplay) continue
            if (entry.device.isBuiltIn && externalPresent && externalLaunched) continue
            val nextFree = (0 until maxPorts).firstOrNull { !occupied[it] } ?: continue
            entry.port = nextFree
            occupied[nextFree] = true
        }

        publish()
    }

    private fun publish() {
        _routes.value = entries.values
            .mapNotNull { it.port?.let { p -> it.device.androidDeviceId to p } }
            .toMap()
    }

    fun evaluatorFor(androidDeviceId: Int): PortEvaluator? =
        entries[resolveId(androidDeviceId)]?.evaluator

    fun mappingFor(androidDeviceId: Int): DeviceMapping? =
        entries[resolveId(androidDeviceId)]?.mapping

    data class Snapshot(
        val androidDeviceId: Int,
        val device: ConnectedDevice,
        val mapping: DeviceMapping,
        val port: Int?,
    )

    fun snapshotEntries(): List<Snapshot> = entries.values.map {
        Snapshot(it.device.androidDeviceId, it.device, it.mapping, it.port)
    }

    fun updateMapping(mapping: DeviceMapping, rebuildEvaluator: Boolean = false) {
        for (entry in entries.values) {
            if (entry.mapping.id == mapping.id) {
                entry.mapping = mapping
                if (rebuildEvaluator) entry.evaluator = PortEvaluator(mapping)
            }
        }
        recompute()
    }

    fun mappingForPort(port: Int): DeviceMapping? =
        entries.values.firstOrNull { it.port == port }?.mapping

    fun snapshotForPort(port: Int): PortSnapshot? =
        entries.values.firstOrNull { it.port == port }?.evaluator?.snapshot()

    fun isCanonicalPressedAt(port: Int, button: CanonicalButton): Boolean {
        val entry = entries.values.firstOrNull { it.port == port } ?: return false
        return entry.evaluator.currentlyPressed().contains(button)
    }

    fun analogValueAt(port: Int, role: AnalogRole): Float {
        val entry = entries.values.firstOrNull { it.port == port } ?: return 0f
        return entry.evaluator.analogValue(role)
    }

    fun resetAllEvaluators() {
        for (entry in entries.values) entry.evaluator.resetState()
    }
}
