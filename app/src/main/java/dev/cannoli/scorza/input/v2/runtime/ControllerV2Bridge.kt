package dev.cannoli.scorza.input.v2.runtime

import android.content.Context
import android.hardware.input.InputManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import androidx.annotation.VisibleForTesting
import dev.cannoli.scorza.input.autoconfig.RetroArchCfgEntry
import dev.cannoli.scorza.input.v2.CanonicalButton
import dev.cannoli.scorza.input.v2.ConnectedDevice
import dev.cannoli.scorza.input.v2.hints.ControllerHintTable
import dev.cannoli.scorza.input.v2.resolver.MappingResolver

class ControllerV2Bridge(
    private val resolver: MappingResolver,
    private val portRouter: PortRouter,
    private val activeMappingHolder: ActiveMappingHolder,
    private val physicalIdentityResolver: PhysicalIdentityResolver,
    private val btTracker: BtHidConnectionTracker? = null,
    private val mappingRepository: dev.cannoli.scorza.input.v2.repo.MappingRepository? = null,
    private val blacklist: dev.cannoli.scorza.input.ControllerBlacklist? = null,
    private val bundledCfgs: List<RetroArchCfgEntry> = emptyList(),
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

    private val identityToPrimary = mutableMapOf<PhysicalIdentity, Int>()
    private val identityCache = mutableMapOf<Int, PhysicalIdentity>()
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
        btTracker?.start()
        scheduleSettle()
    }

    fun stop(context: Context) {
        val l = listener ?: return
        settleHandler.removeCallbacks(settleRunnable)
        val inputManager = context.getSystemService(Context.INPUT_SERVICE) as InputManager
        inputManager.unregisterInputDeviceListener(l)
        btTracker?.stop()
        identityCache.clear()
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
            if (blacklist?.isBlocked(facts.name, facts.vendorId) == true) {
                dev.cannoli.scorza.util.InputLog.write(
                    "  blacklisted id=${facts.androidDeviceId} name='${facts.name}' vid=${facts.vendorId}"
                )
                continue
            }
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
                isExternal = facts.isExternal,
            )
            currentDevices += connected
            dev.cannoli.scorza.util.InputLog.write(
                "  identify id=${connected.androidDeviceId} name='${connected.name}' vid=${connected.vendorId} pid=${connected.productId}"
            )
        }

        // Pass 1: per-device identify. Only name-match consumes BT queue entries here; FIFO
        // claims happen in pass 2 below so iteration order can't let an early device steal a
        // later device's MAC.
        val identityByDeviceId = mutableMapOf<Int, PhysicalIdentity>()
        val unidentified = mutableListOf<ConnectedDevice>()
        for (device in currentDevices) {
            val cached = identityCache[device.androidDeviceId]
            val id = cached ?: physicalIdentityResolver.identify(device)
            if (id != null) {
                identityByDeviceId[device.androidDeviceId] = id
            } else {
                unidentified += device
            }
        }

        // Pass 2: for any device that landed on Wired but is plausibly a BT controller in
        // disguise (external + cache miss + queue still has entries), upgrade to BT by claiming
        // the next FIFO MAC. Skip if:
        //   - already cached (don't redraw on re-settles)
        //   - !isExternal (best-effort; on some handhelds the built-in still reports external)
        //   - the curated wired-only list matches (handheld built-in keypads)
        val wiredOnlyResolver = physicalIdentityResolver as? BluetoothPhysicalIdentityResolver
        for (device in currentDevices) {
            if (identityCache.containsKey(device.androidDeviceId)) continue
            if (!device.isExternal) continue
            if (wiredOnlyResolver?.isWiredOnly(device.name) == true) continue
            val current = identityByDeviceId[device.androidDeviceId]
            if (current is PhysicalIdentity.Bluetooth) continue
            val mac = physicalIdentityResolver.claimFifoMac() ?: break
            dev.cannoli.scorza.util.InputLog.write(
                "  identify id=${device.androidDeviceId} name='${device.name}' -> BT mac=$mac (fifo)"
            )
            identityByDeviceId[device.androidDeviceId] = PhysicalIdentity.Bluetooth(mac)
        }

        val byIdentity = mutableMapOf<PhysicalIdentity, MutableList<ConnectedDevice>>()
        for (device in currentDevices) {
            val id = identityByDeviceId[device.androidDeviceId]
            if (id != null) {
                byIdentity.getOrPut(id) { mutableListOf() } += device
                identityCache[device.androidDeviceId] = id
            } else if (device !in unidentified) {
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

        val existingSnaps = portRouter.snapshotEntries()
        for (id in existingEntryIds - targetEntryIds) {
            val snap = existingSnaps.firstOrNull { it.androidDeviceId == id }
            val displayName = snap?.mapping?.displayName?.takeIf { it.isNotEmpty() }
                ?: snap?.device?.name?.takeIf { it.isNotEmpty() }
                ?: "Controller"
            val port = snap?.port
            dev.cannoli.scorza.util.InputLog.write("  removed id=$id name='$displayName' port=${port?.let { "P${it + 1}" } ?: "-"}")
            portRouter.onDisconnect(id)
            identityCache.remove(id)
            if (initialEnumerationDone) {
                onDeviceRemoved?.invoke(DepartedDevice(id, displayName, port))
            }
        }

        for (id in targetEntryIds - existingEntryIds) {
            val connected = targetEntries.getValue(id)
            val identity = identityByDeviceId[id]
            val btMac = (identity as? PhysicalIdentity.Bluetooth)?.macAddress
            // cfg-vidpid-override is only useful for finding an RA cfg on first pair. Once we
            // have a saved mapping keyed on this MAC, the resolver hits it via MAC score and
            // doesn't need the override at all. Skip the override in that case.
            val hasSavedMacMapping = btMac != null && mappingRepository?.list()?.any {
                it.match.bluetoothMac.equals(btMac, ignoreCase = true)
            } == true
            val deviceForResolver = if (identity is PhysicalIdentity.Bluetooth && !hasSavedMacMapping) {
                cfgCorrectedDevice(connected)
            } else {
                connected
            }
            val resolved = resolver.resolve(deviceForResolver, btMac)
            // Re-apply the hint table using the ORIGINAL device VID/PID (not the cfg-corrected
            // one), so a Sony VID gets SHAPES even when the cfg-vidpid override pointed at a
            // Retroid cfg. User-edited mappings keep their stored hint values.
            val hintApplied = applyHintFromOriginalIdentity(resolved.mapping, connected)
            // Stamp the BT MAC onto the mapping's match rule so future re-pairs hit by MAC even
            // if the kernel decorates the InputDevice with a different name/VID.
            val needsMacStamp = btMac != null && hintApplied.match.bluetoothMac != btMac
            val finalMapping = if (needsMacStamp) {
                hintApplied.copy(match = hintApplied.match.copy(bluetoothMac = btMac))
            } else hintApplied
            // Persist when our auto-applied logic changed something (new MAC stamp, or a
            // corrected glyph/confirm from the hint pass) so the change sticks across restarts.
            // Never write back over a userEdited mapping — the user curated it, leave it alone.
            val hintChanged = hintApplied !== resolved.mapping
            if (!finalMapping.userEdited && (needsMacStamp || hintChanged)) {
                mappingRepository?.save(finalMapping)
            }
            portRouter.onConnect(connected, finalMapping)
            activeMappingHolder.set(finalMapping)
            val port = portRouter.portFor(connected.androidDeviceId)
            dev.cannoli.scorza.util.InputLog.write(
                "  enrolled id=${connected.androidDeviceId} mapping=${finalMapping.id} persistent=${resolved.persistent} glyph=${finalMapping.glyphStyle} mac=${btMac ?: "-"} port=${port?.let { "P${it + 1}" } ?: "-"}"
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

    private fun applyHintFromOriginalIdentity(
        mapping: dev.cannoli.scorza.input.v2.DeviceMapping,
        connected: ConnectedDevice,
    ): dev.cannoli.scorza.input.v2.DeviceMapping {
        if (mapping.userEdited) return mapping
        val table = hints ?: return mapping
        // Try the device's reported VID/PID first (real brand identity for controllers like
        // Xbox where the kernel reports the manufacturer VID). If that doesn't match, walk
        // through every bundled cfg whose deviceName equals this device's name and try each
        // one's VID/PID against the hint table. This catches the case where the kernel reports
        // an AMICON-fallback VID (8226) and our cfg picker happens to grab a Retroid-flavored
        // cfg with the same fallback VID, missing a separate cfg with the controller's real
        // manufacturer VID. Finally fall through to Build.MODEL / default.
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
     * already tried) and return the first cfg's hint that matches the hint table. Returns the
     * matched hint plus the (vid, pid) used for logging, or null if no cfg yields a hint.
     */
    private fun cfgHintForName(
        table: ControllerHintTable,
        name: String,
        skipVendorId: Int,
        skipProductId: Int,
    ): Pair<dev.cannoli.scorza.input.v2.hints.ControllerHint, Pair<Int, Int>>? {
        if (name.isEmpty()) return null
        for (entry in bundledCfgs) {
            if (entry.deviceName != name) continue
            val vid = entry.vendorId ?: continue
            val pid = entry.productId ?: continue
            if (vid == skipVendorId && pid == skipProductId) continue
            val hint = table.lookupVidPid(vid, pid) ?: continue
            return hint to (vid to pid)
        }
        return null
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
        isExternal = isExternal,
    )

    companion object {
        const val SOURCE_GAMEPAD: Int = InputDevice.SOURCE_GAMEPAD
        const val SOURCE_JOYSTICK: Int = InputDevice.SOURCE_JOYSTICK
        private const val SETTLE_DELAY_MS = 500L
    }
}
