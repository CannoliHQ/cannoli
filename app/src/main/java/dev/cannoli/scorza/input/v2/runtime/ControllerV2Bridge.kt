package dev.cannoli.scorza.input.v2.runtime

import android.content.Context
import android.hardware.input.InputManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import androidx.annotation.VisibleForTesting
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

    private val identityToPrimary = mutableMapOf<PhysicalIdentity, Int>()
    private var listener: InputManager.InputDeviceListener? = null
    private var initialEnumerationDone = false
    private var appContext: Context? = null

    private val settleHandler: Handler by lazy { Handler(Looper.getMainLooper()) }
    private val settleRunnable = Runnable {
        settle()
        if (!initialEnumerationDone) {
            initialEnumerationDone = true
            dev.cannoli.scorza.util.InputLog.write("--- initial enumeration done ---")
        }
    }

    var onDeviceAdded: ((ConnectedDevice) -> Unit)? = null
    var onDeviceRemoved: ((Int) -> Unit)? = null

    fun markInitialEnumerationDone() { initialEnumerationDone = true }

    fun start(context: Context) {
        if (listener != null) return
        appContext = context.applicationContext
        dev.cannoli.scorza.util.InputLog.write("--- bridge start (Build.MODEL='$buildModel') ---")
        val inputManager = context.getSystemService(Context.INPUT_SERVICE) as InputManager
        val l = object : InputManager.InputDeviceListener {
            override fun onInputDeviceAdded(deviceId: Int) {
                val facts = InputDevice.getDevice(deviceId)?.toFacts() ?: return
                handleDeviceAdded(facts)
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
        scheduleSettle()
    }

    fun stop(context: Context) {
        val l = listener ?: return
        settleHandler.removeCallbacks(settleRunnable)
        val inputManager = context.getSystemService(Context.INPUT_SERVICE) as InputManager
        inputManager.unregisterInputDeviceListener(l)
        initialEnumerationDone = false
        listener = null
        appContext = null
    }

    fun markLaunchTrigger(androidDeviceId: Int) {
        portRouter.markLaunchTrigger(androidDeviceId)
    }

    fun handleDeviceAdded(facts: DeviceFacts) {
        dev.cannoli.scorza.util.InputLog.write(
            "event added id=${facts.androidDeviceId} desc='${facts.descriptor}' name='${facts.name}' vid=${facts.vendorId} pid=${facts.productId} src=0x${facts.sourceMask.toString(16)}"
        )
        scheduleSettle()
    }

    fun handleDeviceRemoved(androidDeviceId: Int) {
        dev.cannoli.scorza.util.InputLog.write("event removed id=$androidDeviceId")
        scheduleSettle()
    }

    /**
     * Cancel any pending settle and run one immediately. Used after BLUETOOTH_CONNECT is granted
     * so previously-enrolled phantoms can be merged without waiting for the next kernel event.
     */
    fun settleNow() {
        settleHandler.removeCallbacks(settleRunnable)
        settleHandler.post(settleRunnable)
    }

    private fun scheduleSettle() {
        settleHandler.removeCallbacks(settleRunnable)
        settleHandler.postDelayed(settleRunnable, SETTLE_DELAY_MS)
    }

    @VisibleForTesting
    fun settleSyncForTest(facts: List<DeviceFacts>) {
        settle(facts)
        if (!initialEnumerationDone) {
            initialEnumerationDone = true
        }
    }

    private fun enumerateGamepadFacts(): List<DeviceFacts> {
        val out = mutableListOf<DeviceFacts>()
        for (id in InputDevice.getDeviceIds()) {
            val device = InputDevice.getDevice(id) ?: continue
            out += device.toFacts()
        }
        return out
    }

    private fun settle(forcedFacts: List<DeviceFacts>? = null) {
        dev.cannoli.scorza.util.InputLog.write("--- settle ---")

        val factsList = forcedFacts ?: enumerateGamepadFacts()

        val currentDevices = mutableListOf<ConnectedDevice>()
        for (facts in factsList) {
            if (!isGamepad(facts)) continue
            val zeroVidPid = facts.vendorId == 0 && facts.productId == 0
            if (zeroVidPid && facts.name.isNullOrEmpty()) continue
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
            currentDevices += connected
            dev.cannoli.scorza.util.InputLog.write(
                "  identify id=${connected.androidDeviceId} name='${connected.name}' vid=${connected.vendorId} pid=${connected.productId}"
            )
        }

        val byIdentity = mutableMapOf<PhysicalIdentity, MutableList<ConnectedDevice>>()
        val unidentified = mutableListOf<ConnectedDevice>()
        for (device in currentDevices) {
            val id = physicalIdentityResolver.identify(device)
            if (id != null) {
                byIdentity.getOrPut(id) { mutableListOf() } += device
            } else {
                unidentified += device
            }
        }

        val targetEntries = mutableMapOf<Int, ConnectedDevice>()
        val targetAliases = mutableMapOf<Int, Int>()
        val targetIdentityToPrimary = mutableMapOf<PhysicalIdentity, Int>()
        for ((identity, group) in byIdentity) {
            val primary = group.minBy { it.androidDeviceId }
            targetEntries[primary.androidDeviceId] = primary
            targetIdentityToPrimary[identity] = primary.androidDeviceId
            for (other in group) {
                if (other.androidDeviceId != primary.androidDeviceId) {
                    targetAliases[other.androidDeviceId] = primary.androidDeviceId
                    dev.cannoli.scorza.util.InputLog.write(
                        "  phantom-alias: id=${other.androidDeviceId} identity=$identity -> primary id=${primary.androidDeviceId}"
                    )
                }
            }
        }
        for (device in unidentified) {
            targetEntries[device.androidDeviceId] = device
        }

        val existingEntryIds = portRouter.snapshotEntries().map { it.androidDeviceId }.toSet()
        val targetEntryIds = targetEntries.keys

        for (id in existingEntryIds - targetEntryIds) {
            dev.cannoli.scorza.util.InputLog.write("  removed id=$id")
            portRouter.onDisconnect(id)
            if (initialEnumerationDone) onDeviceRemoved?.invoke(id)
        }

        for (id in targetEntryIds - existingEntryIds) {
            val connected = targetEntries.getValue(id)
            val identity = physicalIdentityResolver.identify(connected)
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
                "  enrolled id=${connected.androidDeviceId} mapping=${resolved.mapping.id} persistent=${resolved.persistent} port=${port?.let { "P${it + 1}" } ?: "-"}"
            )
            if (initialEnumerationDone) onDeviceAdded?.invoke(connected)
        }

        val currentAliases = portRouter.aliasesSnapshot()
        for ((aliasId, primaryId) in currentAliases) {
            if (targetAliases[aliasId] != primaryId) {
                portRouter.removeAlias(aliasId)
            }
        }
        for ((aliasId, primaryId) in targetAliases) {
            if (currentAliases[aliasId] != primaryId) {
                portRouter.addAlias(primaryId, aliasId)
            }
        }

        identityToPrimary.clear()
        identityToPrimary.putAll(targetIdentityToPrimary)
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
        private const val SETTLE_DELAY_MS = 500L
    }
}
