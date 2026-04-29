package dev.cannoli.scorza.input.v2.runtime

import dev.cannoli.scorza.input.v2.AnalogRole
import dev.cannoli.scorza.input.v2.CanonicalButton
import dev.cannoli.scorza.input.v2.ConnectedDevice
import dev.cannoli.scorza.input.v2.DeviceTemplate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class PortRouter(private val maxPorts: Int = 4) {

    private data class Entry(
        val device: ConnectedDevice,
        val template: DeviceTemplate,
        var port: Int?,
        val evaluator: PortEvaluator,
    )

    private val entries = linkedMapOf<Int, Entry>()
    private var launcherTriggerDeviceId: Int? = null

    private val _routes = MutableStateFlow<Map<Int, Int>>(emptyMap())
    val routes: StateFlow<Map<Int, Int>> = _routes.asStateFlow()

    fun onConnect(device: ConnectedDevice, template: DeviceTemplate) {
        if (entries.containsKey(device.androidDeviceId)) return
        entries[device.androidDeviceId] = Entry(device, template, port = null, evaluator = PortEvaluator(template))
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

        val externalPresent = entries.values.any { !it.device.isBuiltIn && !it.template.excludeFromGameplay }
        val externalLaunched = launchEntry != null && !launchEntry.device.isBuiltIn

        val occupied = BooleanArray(maxPorts)

        if (launchEntry != null && !launchEntry.template.excludeFromGameplay) {
            launchEntry.port = 0
            occupied[0] = true
        }

        for (entry in ordered) {
            if (entry == launchEntry) continue
            if (entry.template.excludeFromGameplay) continue
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

    fun templateFor(androidDeviceId: Int): DeviceTemplate? =
        entries[androidDeviceId]?.template

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
