package dev.cannoli.scorza.input

import android.content.Context
import android.hardware.input.InputManager
import android.view.InputDevice
import dev.cannoli.scorza.libretro.LibretroInput
import dev.cannoli.scorza.libretro.LibretroRunner
import org.json.JSONObject

data class ControllerIdentity(
    val descriptor: String,
    val name: String
)

class ControllerManager(
    private val maxPorts: Int = LibretroRunner.MAX_PORTS
) : InputManager.InputDeviceListener {

    val slots = arrayOfNulls<ControllerIdentity>(maxPorts)
    val portInputs = Array(maxPorts) { LibretroInput() }
    val portInputMasks = IntArray(maxPorts)
    val portPressedKeys = Array(maxPorts) { mutableSetOf<Int>() }

    private val deviceToPort = mutableMapOf<Int, Int>()
    private val descriptorToPort = mutableMapOf<String, Int>()
    private val startupDeviceIds = mutableSetOf<Int>()
    private var blacklistedVendors = emptySet<Int>()
    private var blacklistedPrefixes = emptyList<String>()

    var onDeviceDisconnected: ((port: Int) -> Unit)? = null
    var onDeviceConnected: ((port: Int, identity: ControllerIdentity) -> Unit)? = null

    val connectedPortCount: Int get() = slots.count { it != null }

    fun loadBlacklist(context: Context) {
        try {
            val json = JSONObject(context.assets.open("controller_blacklist.json").bufferedReader().readText())
            val vendors = json.optJSONArray("vendors")
            if (vendors != null) {
                blacklistedVendors = (0 until vendors.length()).map { vendors.getJSONObject(it).getInt("id") }.toSet()
            }
            val prefixes = json.optJSONArray("name_prefixes")
            if (prefixes != null) {
                blacklistedPrefixes = (0 until prefixes.length()).map { prefixes.getString(it) }
            }
        } catch (_: Exception) {}
    }

    private fun isBlacklisted(device: InputDevice): Boolean {
        if (device.vendorId in blacklistedVendors) return true
        val name = device.name
        return blacklistedPrefixes.any { name.startsWith(it, ignoreCase = true) }
    }

    fun initialize() {
        val ids = InputDevice.getDeviceIds()
        val fullControllers = mutableListOf<Pair<Int, InputDevice>>()
        val subDevices = mutableListOf<Int>()
        for (id in ids) {
            val device = InputDevice.getDevice(id) ?: continue
            if (!isGameController(device)) continue
            startupDeviceIds.add(id)
            if (isBlacklisted(device)) continue
            if (isFullController(device)) fullControllers.add(id to device)
            else subDevices.add(id)
        }
        for ((id, device) in fullControllers) {
            val port = nextAvailablePort() ?: break
            val identity = ControllerIdentity(device.descriptor, device.name)
            slots[port] = identity
            deviceToPort[id] = port
            descriptorToPort[device.descriptor] = port
        }
        for (id in subDevices) {
            deviceToPort[id] = 0
        }
        if (slots[0] == null && subDevices.isNotEmpty()) {
            slots[0] = ControllerIdentity("builtin", "Built-in Controller")
        }
    }

    fun assignDevice(deviceId: Int): Int {
        deviceToPort[deviceId]?.let { return it }

        val device = InputDevice.getDevice(deviceId) ?: return -1
        if (isBlacklisted(device)) return -1
        val descriptor = device.descriptor

        val existingPort = descriptorToPort[descriptor]
        if (existingPort != null) {
            val identity = ControllerIdentity(descriptor, device.name)
            slots[existingPort] = identity
            deviceToPort[deviceId] = existingPort
            onDeviceConnected?.invoke(existingPort, identity)
            return existingPort
        }

        val port = nextAvailablePort() ?: return -1
        val identity = ControllerIdentity(descriptor, device.name)
        slots[port] = identity
        deviceToPort[deviceId] = port
        descriptorToPort[descriptor] = port
        onDeviceConnected?.invoke(port, identity)
        return port
    }

    private fun nextAvailablePort(): Int? = (0 until maxPorts).firstOrNull { slots[it] == null }

    fun removeDevice(deviceId: Int): Int? {
        val port = deviceToPort.remove(deviceId) ?: return null
        slots[port] = null
        portInputMasks[port] = 0
        portPressedKeys[port].clear()
        onDeviceDisconnected?.invoke(port)
        return port
    }

    fun getPortForDeviceId(deviceId: Int): Int? {
        deviceToPort[deviceId]?.let { return it }
        if (deviceId in startupDeviceIds) {
            deviceToPort[deviceId] = 0
            return 0
        }
        return null
    }

    fun resetAllInput() {
        for (p in 0 until maxPorts) {
            portInputMasks[p] = 0
            portPressedKeys[p].clear()
        }
    }

    override fun onInputDeviceAdded(deviceId: Int) {
        val device = InputDevice.getDevice(deviceId) ?: return
        if (!isGameController(device)) return
        assignDevice(deviceId)
    }

    override fun onInputDeviceRemoved(deviceId: Int) {
        removeDevice(deviceId)
    }

    override fun onInputDeviceChanged(deviceId: Int) {}

    companion object {
        fun isGameController(device: InputDevice): Boolean {
            if (device.vendorId == 0 && device.productId == 0) return false
            val sources = device.sources
            return sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
                    sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK
        }

        fun isFullController(device: InputDevice): Boolean {
            if (device.vendorId == 0 && device.productId == 0) return false
            val sources = device.sources
            return sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
                    sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK
        }
    }
}
