package dev.cannoli.scorza.input.runtime

import android.content.Context
import android.hardware.input.InputManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import androidx.annotation.VisibleForTesting
import dev.cannoli.scorza.input.CanonicalButton
import dev.cannoli.scorza.input.ConnectedDevice
import dev.cannoli.scorza.input.hints.ControllerHintTable
import dev.cannoli.scorza.input.resolver.MappingResolver

class ControllerBridge(
    private val resolver: MappingResolver,
    private val portRouter: PortRouter,
    private val activeMappingHolder: ActiveMappingHolder,
    private val mappingRepository: dev.cannoli.scorza.input.repo.MappingRepository? = null,
    private val blacklist: dev.cannoli.scorza.input.ControllerBlacklist? = null,
    private val bundledCfgs: dev.cannoli.scorza.input.autoconfig.BundledAutoconfigEntries? = null,
    private val hints: ControllerHintTable? = null,
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
        val isExternal: Boolean = true,
    )

    private var listener: InputManager.InputDeviceListener? = null
    private var initialEnumerationDone = false
    private var appContext: Context? = null
    private val pendingSavesById = mutableMapOf<Int, dev.cannoli.scorza.input.DeviceMapping>()

    init {
        portRouter.onActivatedListener = { device -> handleActivation(device) }
    }

    private val settleHandler: Handler by lazy { Handler(Looper.getMainLooper()) }
    private val settleRunnable = Runnable {
        settle()
        if (!initialEnumerationDone) {
            initialEnumerationDone = true
            dev.cannoli.scorza.util.InputLog.write("--- initial enumeration done ---")
        }
    }

    var onDeviceAdded: ((ConnectedDevice) -> Unit)? = null
    var onDeviceRemoved: ((DepartedDevice) -> Unit)? = null

    data class DepartedDevice(
        val androidDeviceId: Int,
        val displayName: String,
        val port: Int?,
    )

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
        pendingSavesById.clear()
        initialEnumerationDone = false
        listener = null
        appContext = null
    }

    private fun handleActivation(device: ConnectedDevice) {
        val id = device.androidDeviceId
        pendingSavesById.remove(id)?.let { mappingRepository?.save(it) }
        portRouter.mappingFor(id)?.let { activeMappingHolder.set(it) }
        // Activation is always a deliberate user press, so fire onDeviceAdded regardless of
        // whether the device was present during the initial enumeration burst. Suppression for
        // built-in devices is handled by the OSD layer, not here.
        onDeviceAdded?.invoke(device)
    }

    @VisibleForTesting
    fun handleActivationForTest(device: ConnectedDevice) = handleActivation(device)

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

    /** Cancel any pending settle and run one immediately. */
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

    private fun enumerateFacts(): List<DeviceFacts> {
        val out = mutableListOf<DeviceFacts>()
        for (id in InputDevice.getDeviceIds()) {
            val device = InputDevice.getDevice(id) ?: continue
            out += device.toFacts()
        }
        return out
    }

    private fun settle(forcedFacts: List<DeviceFacts>? = null) {
        dev.cannoli.scorza.util.InputLog.write("--- settle ---")

        val factsList = forcedFacts ?: enumerateFacts()

        // Build the sibling-folding candidate set from ALL InputDevices, not just gamepads, so the
        // auxiliary endpoints (touchpad/IMU/keyboard/mouse) can contribute their MAC-bearing
        // descriptors when the gamepad's own is degenerate.
        val candidates = mutableListOf<SiblingFolder.Candidate>()
        for (facts in factsList) {
            val gamepad = isGamepad(facts)
            if (gamepad && blacklist?.isBlocked(facts.name, facts.vendorId) == true) {
                dev.cannoli.scorza.util.InputLog.write(
                    "  blacklisted id=${facts.androidDeviceId} name='${facts.name}' vid=${facts.vendorId}"
                )
                continue
            }
            val zeroVidPid = facts.vendorId == 0 && facts.productId == 0
            if (gamepad && zeroVidPid && facts.name.isNullOrEmpty()) continue
            candidates += SiblingFolder.Candidate(
                androidDeviceId = facts.androidDeviceId,
                name = facts.name ?: "",
                descriptor = facts.descriptor ?: "",
                isGamepad = gamepad,
            )
        }

        val clusters = SiblingFolder.fold(candidates)
        val factsById = factsList.associateBy { it.androidDeviceId }

        val targetEntries = mutableMapOf<Int, ConnectedDevice>()
        val targetAliases = mutableMapOf<Int, Int>()
        val clusterDescriptors = mutableMapOf<Int, String>()
        for (cluster in clusters) {
            val gamepadFacts = factsById[cluster.gamepad.androidDeviceId] ?: continue
            // Retroid (and likely other handhelds) lie via InputDevice.isExternal and report
            // their internal pad as external. Fall back to a name-vs-Build.MODEL prefix check:
            // an internal pad almost always reports a name that starts with the handheld brand
            // (e.g. Build.MODEL='Retroid Pocket Classic' + name='Retroid Pocket Controller').
            val nameLooksInternal = nameMatchesBuildModelBrand(gamepadFacts.name, buildModel)
            val connected = ConnectedDeviceFactory.fromFields(
                androidDeviceId = gamepadFacts.androidDeviceId,
                descriptor = gamepadFacts.descriptor,
                name = gamepadFacts.name,
                vendorId = gamepadFacts.vendorId,
                productId = gamepadFacts.productId,
                androidBuildModel = buildModel,
                sourceMask = gamepadFacts.sourceMask,
                connectedAtMillis = clock(),
                isBuiltIn = !gamepadFacts.isExternal || nameLooksInternal,
                isExternal = gamepadFacts.isExternal,
            )
            targetEntries[connected.androidDeviceId] = connected
            clusterDescriptors[connected.androidDeviceId] = cluster.persistenceDescriptor
            dev.cannoli.scorza.util.InputLog.write(
                "  identify id=${connected.androidDeviceId} name='${connected.name}' vid=${connected.vendorId} pid=${connected.productId} desc='${cluster.persistenceDescriptor}'"
            )
            for (alias in cluster.aliases) {
                targetAliases[alias.androidDeviceId] = connected.androidDeviceId
                dev.cannoli.scorza.util.InputLog.write(
                    "  phantom-alias: id=${alias.androidDeviceId} name='${alias.name}' -> primary id=${connected.androidDeviceId}"
                )
            }
        }

        val existingEntryIds = portRouter.snapshotEntries().map { it.androidDeviceId }.toSet()
        val targetEntryIds = targetEntries.keys

        val existingSnaps = portRouter.snapshotEntries()
        for (id in existingEntryIds - targetEntryIds) {
            val snap = existingSnaps.firstOrNull { it.androidDeviceId == id }
            val displayName = snap?.mapping?.displayName?.takeIf { it.isNotEmpty() }
                ?: snap?.device?.name?.takeIf { it.isNotEmpty() }
                ?: "Controller"
            val port = snap?.port
            dev.cannoli.scorza.util.InputLog.write("  removed id=$id name='$displayName' port=${port?.let { "P${it + 1}" } ?: "-"}")
            pendingSavesById.remove(id)
            portRouter.onDisconnect(id)
            if (initialEnumerationDone) {
                onDeviceRemoved?.invoke(DepartedDevice(id, displayName, port))
            }
        }

        for (id in targetEntryIds - existingEntryIds) {
            val connected = targetEntries.getValue(id)
            val persistenceDescriptor = clusterDescriptors[id]
            val resolved = resolver.resolve(connected, persistenceDescriptor)
            val hintApplied = applyHintFromOriginalIdentity(resolved.mapping, connected)
            // Park the save until the device actually proves itself by producing input. Never write
            // back over a userEdited mapping. Phantom stubs that never fire never get persisted.
            val hintChanged = hintApplied !== resolved.mapping
            if (!hintApplied.userEdited && hintChanged) {
                pendingSavesById[connected.androidDeviceId] = hintApplied
            }
            portRouter.onConnect(connected, hintApplied)
            dev.cannoli.scorza.util.InputLog.write(
                "  enrolled id=${connected.androidDeviceId} mapping=${hintApplied.id} persistent=${resolved.persistent} glyph=${hintApplied.glyphStyle} desc='${persistenceDescriptor ?: "-"}' pending"
            )
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
    }

    private fun applyHintFromOriginalIdentity(
        mapping: dev.cannoli.scorza.input.DeviceMapping,
        connected: ConnectedDevice,
    ): dev.cannoli.scorza.input.DeviceMapping {
        if (mapping.userEdited) return mapping
        val table = hints ?: return mapping
        // Try the device's reported VID/PID first (real brand identity for controllers like
        // Xbox where the kernel reports the manufacturer VID). If that doesn't match, walk
        // through every bundled cfg whose deviceName equals this device's name and try each
        // one's VID/PID against the hint table. This catches the lying-clone case where the
        // kernel reports an AMICON-fallback VID and our cfg picker happens to grab a cfg with
        // the same fallback VID, missing a separate cfg with the controller's real manufacturer
        // VID. Finally fall through to Build.MODEL / default.
        val hintBySource = table.lookupVidPid(connected.vendorId, connected.productId)
        val nameCfgHint = if (hintBySource == null) {
            cfgHintForName(table, connected.name, connected.vendorId, connected.productId)
        } else null
        val hint = hintBySource ?: nameCfgHint?.first
            ?: table.lookup(connected.vendorId, connected.productId, connected.androidBuildModel)
        if (mapping.menuConfirm == hint.menuConfirm && mapping.glyphStyle == hint.glyphStyle) {
            return mapping
        }
        val source = when {
            hintBySource != null -> "reported vid=${connected.vendorId} pid=${connected.productId}"
            nameCfgHint != null -> "cfg vid=${nameCfgHint.second.first} pid=${nameCfgHint.second.second}"
            else -> "Build.MODEL"
        }
        dev.cannoli.scorza.util.InputLog.write(
            "  hint-rebind: id=${connected.androidDeviceId} via $source -> confirm=${hint.menuConfirm} glyph=${hint.glyphStyle}"
        )
        val menuBack = if (hint.menuConfirm == CanonicalButton.BTN_EAST) CanonicalButton.BTN_SOUTH else CanonicalButton.BTN_EAST
        return mapping.copy(
            menuConfirm = hint.menuConfirm,
            menuBack = menuBack,
            glyphStyle = hint.glyphStyle,
        )
    }

    /**
     * Walk every bundled cfg whose deviceName equals [name] (skipping the reported VID/PID we
     * already tried) and return the first cfg's hint that matches the hint table.
     */
    private fun cfgHintForName(
        table: ControllerHintTable,
        name: String,
        skipVendorId: Int,
        skipProductId: Int,
    ): Pair<dev.cannoli.scorza.input.hints.ControllerHint, Pair<Int, Int>>? {
        if (name.isEmpty()) return null
        val entries = bundledCfgs?.entries() ?: return null
        for (entry in entries) {
            if (entry.deviceName != name) continue
            val vid = entry.vendorId ?: continue
            val pid = entry.productId ?: continue
            if (vid == skipVendorId && pid == skipProductId) continue
            val hint = table.lookupVidPid(vid, pid) ?: continue
            return hint to (vid to pid)
        }
        return null
    }

    private fun nameMatchesBuildModelBrand(deviceName: String?, buildModel: String): Boolean {
        if (deviceName.isNullOrEmpty() || buildModel.isEmpty()) return false
        val brand = buildModel.substringBefore(' ').trim()
        if (brand.length < 3) return false
        return deviceName.startsWith("$brand ", ignoreCase = true) ||
            deviceName.equals(brand, ignoreCase = true)
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
        isExternal = isExternalCompat(),
    )

    companion object {
        const val SOURCE_GAMEPAD: Int = InputDevice.SOURCE_GAMEPAD
        const val SOURCE_JOYSTICK: Int = InputDevice.SOURCE_JOYSTICK
        private const val SETTLE_DELAY_MS = 500L
    }
}
