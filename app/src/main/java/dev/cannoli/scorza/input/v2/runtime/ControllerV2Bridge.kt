package dev.cannoli.scorza.input.v2.runtime

import android.content.Context
import android.hardware.input.InputManager
import android.os.Build
import android.view.InputDevice
import dev.cannoli.scorza.input.autoconfig.RetroArchCfgEntry
import dev.cannoli.scorza.input.v2.ConnectedDevice
import dev.cannoli.scorza.input.v2.resolver.MappingResolver

class ControllerV2Bridge(
    private val resolver: MappingResolver,
    private val portRouter: PortRouter,
    private val activeMappingHolder: ActiveMappingHolder,
    private val physicalIdentityResolver: PhysicalIdentityResolver,
    private val bundledCfgs: List<RetroArchCfgEntry> = emptyList(),
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val buildModel: String = Build.MODEL ?: "",
) {

    data class DeviceFacts(
        val androidDeviceId: Int,
        val descriptor: String?,
        val name: String?,
        val vendorId: Int,
        val productId: Int,
        val sourceMask: Int,
    )

    private val knownDeviceIds = mutableSetOf<Int>()
    private val identityToPrimary = mutableMapOf<PhysicalIdentity, Int>()
    private var listener: InputManager.InputDeviceListener? = null
    private var initialEnumerationDone = false

    var onDeviceAdded: ((ConnectedDevice) -> Unit)? = null
    var onDeviceRemoved: ((Int) -> Unit)? = null

    fun markInitialEnumerationDone() { initialEnumerationDone = true }

    fun start(context: Context) {
        if (listener != null) return
        dev.cannoli.scorza.util.InputLog.write("--- bridge start (Build.MODEL='$buildModel') ---")
        val inputManager = context.getSystemService(Context.INPUT_SERVICE) as InputManager
        val l = object : InputManager.InputDeviceListener {
            override fun onInputDeviceAdded(deviceId: Int) {
                val device = InputDevice.getDevice(deviceId) ?: return
                handleDeviceAdded(device.toFacts())
            }

            override fun onInputDeviceRemoved(deviceId: Int) {
                handleDeviceRemoved(deviceId)
            }

            override fun onInputDeviceChanged(deviceId: Int) {
                val device = InputDevice.getDevice(deviceId) ?: return
                dev.cannoli.scorza.util.InputLog.write(
                    "changed id=$deviceId desc='${device.descriptor}' name='${device.name}' src=0x${device.sources.toString(16)}"
                )
            }
        }
        listener = l
        inputManager.registerInputDeviceListener(l, null)
        for (id in InputDevice.getDeviceIds()) {
            val device = InputDevice.getDevice(id) ?: continue
            handleDeviceAdded(device.toFacts())
        }
        markInitialEnumerationDone()
        dev.cannoli.scorza.util.InputLog.write("--- initial enumeration done ---")
    }

    fun stop(context: Context) {
        val l = listener ?: return
        val inputManager = context.getSystemService(Context.INPUT_SERVICE) as InputManager
        inputManager.unregisterInputDeviceListener(l)
        initialEnumerationDone = false
        listener = null
    }

    fun markLaunchTrigger(androidDeviceId: Int) {
        portRouter.markLaunchTrigger(androidDeviceId)
    }

    fun handleDeviceAdded(facts: DeviceFacts) {
        dev.cannoli.scorza.util.InputLog.write(
            "added id=${facts.androidDeviceId} desc='${facts.descriptor}' name='${facts.name}' vid=${facts.vendorId} pid=${facts.productId} src=0x${facts.sourceMask.toString(16)} initEnum=$initialEnumerationDone"
        )
        if (!isGamepad(facts)) {
            dev.cannoli.scorza.util.InputLog.write("  skip: not gamepad")
            return
        }
        val zeroVidPid = facts.vendorId == 0 && facts.productId == 0
        if (zeroVidPid && facts.name.isNullOrEmpty()) {
            dev.cannoli.scorza.util.InputLog.write("  skip: zero vid/pid and empty name")
            return
        }
        if (!knownDeviceIds.add(facts.androidDeviceId)) {
            dev.cannoli.scorza.util.InputLog.write("  skip: id already known")
            return
        }
        val connected = ConnectedDeviceFactory.fromFields(
            androidDeviceId = facts.androidDeviceId,
            descriptor = facts.descriptor,
            name = facts.name,
            vendorId = facts.vendorId,
            productId = facts.productId,
            androidBuildModel = buildModel,
            sourceMask = facts.sourceMask,
            connectedAtMillis = clock(),
            isBuiltIn = zeroVidPid,
        )

        val identity = physicalIdentityResolver.identify(connected)
        if (identity != null) {
            val existingPrimary = identityToPrimary[identity]
            if (existingPrimary != null && existingPrimary != connected.androidDeviceId) {
                dev.cannoli.scorza.util.InputLog.write(
                    "  phantom-alias: incoming id=${connected.androidDeviceId} identity=$identity -> existing id=$existingPrimary"
                )
                portRouter.addAlias(existingPrimary, connected.androidDeviceId)
                return
            }
            identityToPrimary[identity] = connected.androidDeviceId
        }

        // The kernel sometimes reports the wrong VID/PID for a BT controller (the gamepad
        // endpoint borrows the built-in's HID descriptor, observed on Retroid handhelds). When
        // we have a BT identity, prefer a cfg whose deviceName matches the InputDevice's name --
        // even if the cfg's VID/PID disagrees with what the kernel reported. This routes the
        // device to the right autoconfig.
        val deviceForResolver = if (identity is PhysicalIdentity.Bluetooth) {
            cfgCorrectedDevice(connected)
        } else {
            connected
        }

        val resolved = resolver.resolve(deviceForResolver)
        portRouter.onConnect(connected, resolved.mapping)
        activeMappingHolder.set(resolved.mapping)
        val port = portRouter.portFor(connected.androidDeviceId)
        dev.cannoli.scorza.util.InputLog.write(
            "  enrolled: mapping=${resolved.mapping.id} persistent=${resolved.persistent} port=${port?.let { "P${it + 1}" } ?: "-"}"
        )
        if (initialEnumerationDone) onDeviceAdded?.invoke(connected)
    }

    /**
     * Re-evaluate physical identities for all currently-enrolled devices and alias any
     * duplicates to a single primary. Useful after BLUETOOTH_CONNECT is granted so previously-
     * enrolled phantoms can be merged.
     */
    fun reSettleIdentities() {
        dev.cannoli.scorza.util.InputLog.write("--- reSettleIdentities ---")
        val byIdentity = mutableMapOf<PhysicalIdentity, MutableList<Int>>()
        val unidentified = mutableListOf<Int>()
        for (snap in portRouter.snapshotEntries().sortedBy { it.androidDeviceId }) {
            val identity = physicalIdentityResolver.identify(snap.device)
            if (identity == null) {
                unidentified += snap.androidDeviceId
            } else {
                byIdentity.getOrPut(identity) { mutableListOf() } += snap.androidDeviceId
            }
        }
        identityToPrimary.clear()
        for ((identity, ids) in byIdentity) {
            val primary = ids.first()
            identityToPrimary[identity] = primary
            for (other in ids.drop(1)) {
                dev.cannoli.scorza.util.InputLog.write(
                    "  reSettle alias: id=$other identity=$identity -> primary id=$primary"
                )
                portRouter.addAlias(primary, other)
            }
        }
    }

    fun handleDeviceRemoved(androidDeviceId: Int) {
        if (!knownDeviceIds.remove(androidDeviceId)) {
            dev.cannoli.scorza.util.InputLog.write("removed id=$androidDeviceId (was not tracked)")
            return
        }
        dev.cannoli.scorza.util.InputLog.write("removed id=$androidDeviceId")
        identityToPrimary.entries.removeAll { it.value == androidDeviceId }
        portRouter.onDisconnect(androidDeviceId)
        if (initialEnumerationDone) onDeviceRemoved?.invoke(androidDeviceId)
    }

    private fun cfgCorrectedDevice(connected: ConnectedDevice): ConnectedDevice {
        if (connected.name.isEmpty()) return connected
        val nameMatches = bundledCfgs.filter { it.deviceName == connected.name }
        if (nameMatches.isEmpty()) return connected
        val exactMatch = nameMatches.firstOrNull {
            it.vendorId == connected.vendorId && it.productId == connected.productId
        }
        if (exactMatch != null) return connected
        val withVidPid = nameMatches.firstOrNull { it.vendorId != null && it.productId != null }
            ?: return connected
        val newVid = withVidPid.vendorId!!
        val newPid = withVidPid.productId!!
        if (newVid == connected.vendorId && newPid == connected.productId) return connected
        dev.cannoli.scorza.util.InputLog.write(
            "  cfg-vidpid-override: id=${connected.androidDeviceId} name='${connected.name}' reported vid=${connected.vendorId} pid=${connected.productId} -> cfg vid=$newVid pid=$newPid"
        )
        return connected.copy(vendorId = newVid, productId = newPid)
    }

    private fun isGamepad(facts: DeviceFacts): Boolean {
        val sources = facts.sourceMask
        return (sources and SOURCE_GAMEPAD) == SOURCE_GAMEPAD ||
            (sources and SOURCE_JOYSTICK) == SOURCE_JOYSTICK
    }

    private fun InputDevice.toFacts(): DeviceFacts = DeviceFacts(
        androidDeviceId = id,
        descriptor = descriptor,
        name = name,
        vendorId = vendorId,
        productId = productId,
        sourceMask = sources,
    )

    companion object {
        const val SOURCE_GAMEPAD: Int = InputDevice.SOURCE_GAMEPAD
        const val SOURCE_JOYSTICK: Int = InputDevice.SOURCE_JOYSTICK
    }
}
