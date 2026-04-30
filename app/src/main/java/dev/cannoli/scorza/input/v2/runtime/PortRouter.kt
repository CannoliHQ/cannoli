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
    private var launcherTriggerDeviceId: Int? = null

    private val _routes = MutableStateFlow<Map<Int, Int>>(emptyMap())
    val routes: StateFlow<Map<Int, Int>> = _routes.asStateFlow()

    fun onConnect(device: ConnectedDevice, mapping: DeviceMapping) {
        if (entries.containsKey(device.androidDeviceId)) return
        entries[device.androidDeviceId] = Entry(device, mapping, port = null, evaluator = PortEvaluator(mapping))
        recompute()
    }

    fun onDisconnect(androidDeviceId: Int) {
        entries.remove(androidDeviceId)
        if (launcherTriggerDeviceId == androidDeviceId) launcherTriggerDeviceId = null
        recompute()
    }

    fun markLaunchTrigger(androidDeviceId: Int) {
        launcherTriggerDeviceId = androidDeviceId
        recompute()
    }

    fun portFor(androidDeviceId: Int): Int? = entries[androidDeviceId]?.port

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
        entries[androidDeviceId]?.evaluator

    fun mappingFor(androidDeviceId: Int): DeviceMapping? =
        entries[androidDeviceId]?.mapping

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
}
